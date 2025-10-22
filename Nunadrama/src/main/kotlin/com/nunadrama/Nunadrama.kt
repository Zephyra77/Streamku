package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Movie)

    override val mainPage by lazy {
        mainPageOf(
            "page/%d/?s&search=advanced&post_type=movie" to "All Movies",
            "genre/drama/page/%d/" to "Korean Series",
            "genre/j-drama/page/%d/" to "Japan Series",
            "genre/c-drama/page/%d/" to "China Series",
            "genre/thai-drama/page/%d/" to "Thailand Series",
            "genre/koleksi-series/page/%d/" to "Other Series",
            "genre/variety-show/page/%d/" to "Variety Show"
        )
    }

    private suspend fun request(url: String) = app.get(url)

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = String.format(request.data, page)
        val resp = request("$mainUrl/$urlPath")
        val items = resp.document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query")
        return resp.document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = request(url)
        val document = resp.document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img, div.gmr-movie-img img")?.getImageAttr())?.fixImageQuality()
        val description = document.selectFirst("div.entry-content p")?.text()
            ?: document.selectFirst("div[itemprop=description] p")?.text()
            ?: "Deskripsi tidak ditemukan"
        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val actors = document.select("div.gmr-moviedata span[itemprop=actors]").map { it.text() }

        val epsEls = document.select("div.vid-episodes a, div.gmr-listseries a, div.episodios a, ul.episodios li a")
        val episodes = epsEls.mapIndexedNotNull { index, el ->
            val epUrl = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val epText = el.text().ifBlank { el.attr("title") }.ifBlank { "Episode ${index + 1}" }
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.firstOrNull()?.toIntOrNull()
            newEpisode(epUrl) {
                name = epText
                episode = epNum ?: index + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                addScore(rating)
                addActors(actors)
            }
        }
    }

    companion object {
        private val linkCache = mutableMapOf<String, Pair<Long, List<ExtractorLink>>>()
        private const val CACHE_TTL = 5 * 60 * 1000L
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

        val priorityHosts = listOf("streamwish", "filemoon", "dood", "vidhide", "mixdrop", "sbembed", "userfile", "turbovid", "mirror")
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
}
