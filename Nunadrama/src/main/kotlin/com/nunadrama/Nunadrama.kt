package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class Nunadrama : MainAPI() {

    override var mainUrl = "https://tvnunadrama.store"
    private var directUrl: String? = null
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/page/%d/?s&search=advanced&post_type=movie" to "Rilisan Terbaru",
        "genre/drama/page/%d/" to "K-Drama",
        "genre/j-drama/page/%d/" to "J-Drama",
        "genre/c-drama/page/%d/" to "C-Drama",
        "genre/thai-drama/page/%d/" to "Thai-Drama",
        "genre/variety-show/page/%d/" to "Variety Show"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val home = document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item a").text().trim().replace("-", "")

        val isSeries =
            title.contains("Episode", true) || href.contains("/tv/", true) || select("div.gmr-numbeps").isNotEmpty()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr()?.fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val doc = fetch.document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(doc.selectFirst("figure.pull-left img")?.getImageAttr())?.fixImageQuality()
        val desc = doc.selectFirst("div[itemprop=description] p")?.text()?.trim()
        val ratingText = doc.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()
        val rating = ratingText?.toDoubleOrNull()
        val year = doc.selectFirst("div.gmr-moviedata strong:contains(Year:) a")?.text()?.toIntOrNull()
        val tags = doc.select("div.gmr-moviedata a").map { it.text() }
        val actors = doc.select("span[itemprop=actors] a").map { it.text() }
        val trailer = doc.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val recommendations = doc.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val eps = doc.select("div.gmr-listseries a, div.vid-episodes a").mapNotNull { e ->
            val href = e.attr("href")
            val text = e.text()
            if (text.contains("Segera", true)) return@mapNotNull null
            newEpisode(fixUrl(href)) {
                name = text
                episode = Regex("(\\d+)").find(text)?.groupValues?.firstOrNull()?.toIntOrNull()
            }
        }

        val isSeries = eps.isNotEmpty() || url.contains("/tv/")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, eps) {
                posterUrl = poster
                plot = desc
                this.year = year
                this.tags = tags
                addScore(rating?.let { "%.1f".format(it) })
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = desc
                this.year = year
                this.tags = tags
                addScore(rating?.let { "%.1f".format(it) })
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val now = System.currentTimeMillis()
        linkCache[data]?.let { (timestamp, links) ->
            if (now - timestamp < CACHE_TTL) {
                links.forEach { callback(it) }
                return@coroutineScope true
            } else linkCache.remove(data)
        }

        val doc = app.get(data).document
        val base = getBaseUrl(data)
        val foundLinks = mutableSetOf<String>()

        doc.select("iframe, div.gmr-embed-responsive iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-litespeed-src") }
            val fixed = httpsify(src ?: "")
            if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) foundLinks.add(fixed)
        }

        doc.select("div.mirror_item a").forEach {
            val href = it.attr("href")
            val fixed = httpsify(href)
            if (fixed.isNotBlank()) foundLinks.add(fixed)
        }

        val postId = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!postId.isNullOrEmpty()) {
            val ajax = app.post(
                "$base/wp-admin/admin-ajax.php",
                mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId)
            ).document
            ajax.select("iframe").forEach {
                val src = it.attr("src")
                val fixed = httpsify(src)
                if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) foundLinks.add(fixed)
            }
        }

        val priorityHosts = listOf(
            "streamwish", "filemoon", "dood", "vidhide",
            "mixdrop", "sbembed", "userfile", "turbovid", "mirror"
        )
        val sortedLinks = foundLinks.sortedBy { link ->
            val idx = priorityHosts.indexOfFirst { link.contains(it, true) }
            if (idx == -1) priorityHosts.size else idx
        }

        val extracted = mutableListOf<ExtractorLink>()
        for (link in sortedLinks) {
            try {
                loadExtractor(link, data, subtitleCallback) {
                    callback(it)
                    extracted.add(it)
                }
            } catch (_: Exception) {}
        }

        linkCache[data] = now to extracted
        true
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src").ifBlank {
            attr("abs:data-lazy-src").ifBlank {
                attr("abs:srcset").substringBefore(" ").ifBlank { attr("abs:src") }
            }
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
}
