package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.httpsify
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.text.RegexOption

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

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
    private suspend fun post(url: String, data: Map<String, String>) = app.post(url, data)

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true } ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun isPlaceholderText(t: String?): Boolean {
        if (t.isNullOrBlank()) return true
        val low = t.trim().lowercase()
        return low.contains("segera hadir") || low.contains("coming soon") || low.contains("segera") || low.contains("coming")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality(quality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = String.format(request.data, page)
        val resp = request("$mainUrl/$urlPath")
        val items = resp.document.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return resp.document.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = request(url)
        val baseUrl = getBaseUrl(resp.url)
        val document = resp.document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("Eps")
            ?.trim()
            .orEmpty()

        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] > p")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
            ?: document.selectFirst("div.gmr-moviedata")?.text()
            ?: "Plot Tidak Ditemukan"
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors]").map { it.select("a").text() }

        val isSeries = url.contains("/tv/")
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val epsEls = mutableListOf<Element>()
            epsEls.addAll(document.select("div.vid-episodes a, div.gmr-listseries a, div.episodios a, ul.episodios li a, div.list-episode a"))
            if (epsEls.isEmpty()) {
                val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
                if (!postId.isNullOrEmpty()) {
                    val ajax = post("$baseUrl/wp-admin/admin-ajax.php", mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId))
                    epsEls.addAll(ajax.document.select("a"))
                }
            }

            val episodes = epsEls.mapNotNull { eps ->
                val rawHref = eps.attr("href").takeIf { it.isNotBlank() && it != "#" } ?: return@mapNotNull null
                if (rawHref.contains("coming-soon", true)) return@mapNotNull null
                val href = fixUrl(rawHref)
                val name = eps.text().ifBlank { eps.attr("title").ifBlank { "Episode" } }
                if (isPlaceholderText(name)) return@mapNotNull null
                val epNum = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                }
            }

            val safeEpisodes = if (episodes.isEmpty()) {
                listOf(newEpisode(url) { this.name = "Segera hadir..."; this.episode = 0 })
            } else episodes

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, safeEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                addTrailer(trailer)
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
            links.forEach(callback)
            return@coroutineScope true
        } else {
            linkCache.remove(data)
        }
    }

    val doc = app.get(data).document
    val base = getBaseUrl(data)
    val foundIframes = mutableSetOf<String>()

    doc.select("iframe, div.gmr-embed-responsive iframe").forEach { it ->
        val src = it.attr("src").ifBlank { it.attr("data-litespeed-src") }
        val fixed = httpsify(src ?: "")
        if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) {
            foundIframes.add(fixed)
        }
    }

    val postId = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
    if (!postId.isNullOrEmpty()) {
        val ajax = app.post(
            "$base/wp-admin/admin-ajax.php",
            mapOf(
                "action" to "muvipro_player_content",
                "tab" to "server",
                "post_id" to postId
            )
        ).document

        ajax.select("iframe").forEach { it ->
            val src = it.attr("src")
            val fixed = httpsify(src)
            if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) {
                foundIframes.add(fixed)
            }
        }
    }

    doc.select("ul.gmr-download-list li a").forEach { linkEl ->
        val dlUrl = linkEl.attr("href")
        if (dlUrl.isNotBlank() && !dlUrl.contains("coming-soon", true)) {
            foundIframes.add(httpsify(dlUrl))
        }
    }

    val priorityHosts = listOf("streamwish", "filemoon", "dood", "vidhide", "mixdrop", "sbembed")
    val sortedIframes = foundIframes.sortedBy { link ->
        val idx = priorityHosts.indexOfFirst { link.contains(it, true) }
        if (idx == -1) priorityHosts.size else idx
    }

    sortedIframes.forEach { link ->
        try {
            loadExtractor(link, data, subtitleCallback) { ext ->
                val labeled = newExtractorLink(
                    name = ext.source,
                    source = ext.source,
                    url = ext.url
                )
                callback(labeled)
            }
        } catch (_: Exception) {}
    }

    true
    }
