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
        return attr("abs:data-src").ifBlank {
            attr("abs:data-lazy-src").ifBlank {
                attr("abs:srcset").substringBefore(" ").ifBlank {
                    attr("abs:src")
                }
            }
        }
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return this.replace(regex, "")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a img, div.content-thumbnail img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item a").text().replace("-", "").trim()
        val isSeries = href.contains("/series/", true) || href.contains("/drama/", true)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = request("$mainUrl/${String.format(request.data, page)}")
        val items = resp.document.select("article[itemscope], article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query")
        return resp.document.select("article[itemscope], article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left img, div.gmr-movie-img img")?.getImageAttr())?.fixImageQuality()
        val description = document.selectFirst("div.entry-content p, div[itemprop=description] p")?.text()
        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val actors = document.select("div.gmr-moviedata span[itemprop=actors]").map { it.text() }

        val epsEls = document.select("div.gmr-listseries a, div.vid-episodes a, div.eps a, ul.episodios li a")
        val isSeries = epsEls.isNotEmpty()

        val episodes = epsEls.mapIndexedNotNull { index, el ->
            val epUrl = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val epText = el.text().ifBlank { el.attr("title") }.ifBlank { "Episode ${index + 1}" }
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.firstOrNull()?.toIntOrNull()
            newEpisode(epUrl) {
                name = epText
                episode = epNum ?: index + 1
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
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
        linkCache[data]?.let { (time, links) ->
            if (now - time < CACHE_TTL) {
                links.forEach(callback)
                return@coroutineScope true
            } else linkCache.remove(data)
        }

        val doc = app.get(data).document
        val base = getBaseUrl(data)
        val foundLinks = mutableSetOf<String>()

        doc.select("iframe, div.gmr-embed-responsive iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-litespeed-src") }
            val fixed = httpsify(src ?: "")
            if (fixed.isNotBlank() && !fixed.contains("about:blank")) foundLinks.add(fixed)
        }

        doc.select("div.mirror_item a").forEach {
            val href = it.attr("href")
            if (href.isNotBlank()) foundLinks.add(httpsify(href))
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
                if (fixed.isNotBlank() && !fixed.contains("about:blank")) foundLinks.add(fixed)
            }
        }

        val priorityHosts = listOf("streamwish", "filemoon", "dood", "vidhide", "mixdrop", "sbembed")
        val sortedLinks = foundLinks.sortedBy {
            val i = priorityHosts.indexOfFirst { host -> it.contains(host, true) }
            if (i == -1) priorityHosts.size else i
        }

        val collected = mutableListOf<ExtractorLink>()
        for (link in sortedLinks) {
            try {
                loadExtractor(link, data, subtitleCallback) {
                    collected.add(it)
                    callback(it)
                }
            } catch (_: Exception) {}
        }

        linkCache[data] = now to collected
        true
    }
}
