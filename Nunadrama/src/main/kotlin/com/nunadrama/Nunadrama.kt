package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI

class Nunadrama : MainAPI() {
    override var mainUrl: String = "https://tvnunadrama.store"
    private var directUrl: String? = null
    private val linkCache = mutableMapOf<String, Pair<Long, List<ExtractorLink>>>()
    private val CACHE_TTL = 1000L * 60 * 5
    override var name: String = "Nunadrama"
    override var lang: String = "id"
    override val hasMainPage: Boolean = true
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
        val url = "$mainUrl/${request.data.format(page)}"
        val res = app.get(url)
        val doc = res.document
        val items = doc.select("article.item, article[itemscope], div.card, div.bs, div.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src")
            .ifBlank { attr("abs:data-lazy-src") }
            .ifBlank { attr("abs:srcset").substringBefore(" ") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("src") }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleRaw = selectFirst("h2.entry-title a, h2 a, h3 a, .title a")?.text()?.trim() ?: return null
        val title = titleRaw.substringBefore("Season").substringBefore("Episode").substringBefore("Eps").let { removeBloatx(it) }
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val poster = fixUrlNull(selectFirst("a img, a > img, img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item a").text().trim().replace("-", "")
        val isSeries = titleRaw.contains("Episode", true) || href.contains("/tv/", true) || select("div.gmr-numbeps").isNotEmpty()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (quality.isNotBlank()) addQuality(quality)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val t = selectFirst("a > span.idmuvi-rp-title, .idmuvi-rp-title")?.text()?.trim() ?: return null
        val title = t.substringBefore("Season").substringBefore("Episode").substringBefore("Eps").let { removeBloatx(it) }
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}&post_type[]=post&post_type[]=tv"
        val res = app.get(url)
        return res.document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)
        directUrl = getBaseUrl(res.url)
        val doc = res.document
        val title = doc.selectFirst("h1.entry-title, h1.title")?.text()?.trim()
            ?.substringBefore("Season")?.substringBefore("Episode")?.substringBefore("Eps")?.let { removeBloatx(it) }.orEmpty()
        val poster = fixUrlNull(doc.selectFirst("figure.pull-left img, .wp-post-image, .poster img, .thumb img")?.getImageAttr())?.fixImageQuality()
        val desc = doc.selectFirst("div[itemprop=description] p, .entry-content p, .synopsis p")?.text()?.trim()
        val rating = doc.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val year = doc.select("div.gmr-moviedata:contains(Year:) a, span.gmr-movie-genre:contains(Year:) a").lastOrNull()?.text()?.toIntOrNull()
        val tags = doc.select("div.gmr-moviedata:contains(Genre:) a, span.gmr-movie-genre:contains(Genre:) a").map { it.text() }
        val actors = doc.select("div.gmr-moviedata span[itemprop=actors] a, span[itemprop=actors] a").map { it.text() }
        val trailer = doc.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val recommendations = doc.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val eps = parseEpisodes(doc)

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

    private fun parseEpisodes(doc: Document): List<Episode> {
        val selectors = listOf(
            "div.gmr-listseries a",
            "div.vid-episodes a",
            "ul.episodios li a",
            "div.episodios a",
            "div.dzdesu ul li a",
            "div.box a",
            "div.box p:containsOwn(Episode) + a"
        )
        val eps = mutableListOf<Episode>()
        for (sel in selectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                for (a in els) {
                    val name = a.text().trim()
                    if (name.isBlank()) continue
                    if (name.contains("Segera", true) || name.contains("Coming Soon", true) || name.contains("TBA", true)) continue
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                    val epNum = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    eps.add(newEpisode(fixUrl(href)).apply {
                        this.name = name
                        this.episode = epNum
                    })
                }
                if (eps.isNotEmpty()) return eps
            }
        }

        // fallback parsing
        val html = doc.html()
        val blockRegex = Regex("(?:<p[^>]*>|<div[^>]*>)\\s*Episode\\s+(\\d+)[^<]*(?:</p>|</div>)?(.*?)(?=(?:<p[^>]*>|<div[^>]*>)\\s*Episode\\s+\\d+|$)", RegexOption.DOT_MATCHES_ALL)
        val linkRegex = Regex("<a\\s+[^>]*href=([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        for (m in blockRegex.findAll(html)) {
            val num = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val content = m.groupValues.getOrNull(2) ?: continue
            val links = linkRegex.findAll(content).mapNotNull { it.groupValues.getOrNull(2) }.toList()
            if (links.isNotEmpty()) {
                for (l in links) {
                    eps.add(newEpisode(fixUrl(l)).apply {
                        this.name = "Episode $num"
                        this.episode = num
                    })
                }
            }
        }
        return eps
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val now = System.currentTimeMillis()
        linkCache[data]?.let { (ts, links) ->
            if (now - ts < CACHE_TTL) {
                links.forEach { callback(it) }
                return@coroutineScope true
            } else linkCache.remove(data)
        }

        val originalData = data
        var episodeIndex: Int? = null
        var modData = data

        if (modData.contains("?boxeps-", ignoreCase = true)) {
            try {
                episodeIndex = modData.substringAfter("?boxeps-").toIntOrNull()
                modData = modData.substringBefore("?boxeps")
            } catch (_: Exception) {}
        } else {
            val m = Regex("\\-episode-(\\d+)$").find(modData)
            if (m != null) episodeIndex = m.groupValues.getOrNull(1)?.toIntOrNull()
        }

        val docRes = app.get(modData)
        directUrl = directUrl ?: getBaseUrl(docRes.url)
        val doc = docRes.document

        val found = linkedSetOf<String>()

        doc.select("iframe, div.gmr-embed-responsive iframe, div.embed-responsive iframe, div.player-frame iframe")
            .forEach {
                val src = it.attr("src").ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-litespeed-src") }
                    .ifBlank { it.attr("data-video") }
                    .ifBlank { it.attr("data-player") }
                val fixed = httpsify(src)
                if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) found.add(fixed)
            }

        doc.select("div.mirror_item a, li.server-item a, ul.server-list a, div.server a")
            .forEach {
                val href = it.attr("href")
                val fixed = httpsify(href)
                if (fixed.isNotBlank()) found.add(fixed)
            }

        val postId = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!postId.isNullOrEmpty()) {
            try {
                val ajax = app.post("$directUrl/wp-admin/admin-ajax.php", mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId)).document
                ajax.select("iframe, a").forEach {
                    val src = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("href") }
                    val fixed = httpsify(src)
                    if (fixed.isNotBlank() && !fixed.contains("about:blank", true)) found.add(fixed)
                }
            } catch (_: Exception) {}
        }

        val priorityHosts = listOf("streamwish", "filemoon", "dood", "mixdrop", "terabox", "sbembed", "vidhide", "mirror", "okru", "uqload")
        val sorted = found.toList().sortedBy { link ->
            val idx = priorityHosts.indexOfFirst { link.contains(it, true) }
            if (idx == -1) priorityHosts.size else idx
        }

        val extracted = mutableListOf<ExtractorLink>()
        for (link in sorted) {
            try {
                loadExtractor(link, originalData, subtitleCallback) {
                    callback(it)
                    extracted.add(it)
                }
            } catch (_: Exception) {}
        }

        linkCache[originalData] = now to extracted
        return@coroutineScope extracted.isNotEmpty()
    }

    private fun removeBloatx(title: String): String =
        title.replace(Regex("\uFEFF.*?\uFEFF|\uFEFF.*?\uFEFF"), "").trim()
}
