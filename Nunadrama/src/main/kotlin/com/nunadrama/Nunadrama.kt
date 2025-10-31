package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        val res = app.get("$mainUrl/${request.data.format(page)}")
        val items = res.document.select("article.item, article[itemscope], div.card, div.bs, div.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true } ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? =
        this?.let { replace(Regex("(-\\d*x\\d*)").find(it)?.groupValues?.get(0) ?: it, "") }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun removeBloatx(title: String): String =
        title.replace(Regex("\uFEFF.*?\uFEFF|\uFEFF.*?\uFEFF"), "").trim()

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
        val res = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return res.document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)
        directUrl = getBaseUrl(res.url)
        val doc = res.document
        val title = doc.selectFirst("h1.entry-title, h1.title")?.text()?.trim()
            ?.substringBefore("Season")?.substringBefore("Episode")?.substringBefore("Eps")
            ?.let { removeBloatx(it) }.orEmpty()
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
                    if (name.isBlank() || name.contains("Segera", true) || name.contains("Coming Soon", true) || name.contains("TBA", true)) continue
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

        val doc = app.get(data).document
        directUrl = directUrl ?: getBaseUrl(doc.location())
        val foundLinks = linkedSetOf<String>()
        val id = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            doc.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val iframeUrl = app.get(fixUrl(ele.attr("href"))).document
                    .selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()
                    ?.let { httpsify(it) }
                    ?: return@forEach
                if (iframeUrl.isNotBlank()) foundLinks.add(iframeUrl)
            }
        } else {
            doc.select("div.tab-content-ajax").forEach { ele ->
                val serverUrl = app.post(
                    "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to id)
                ).document.select("iframe").attr("src").let { httpsify(it) }
                if (serverUrl.isNotBlank()) foundLinks.add(serverUrl)
            }
        }

        doc.select("ul.gmr-download-list li a").forEach { linkEl ->
            val dl = linkEl.attr("href")
            if (dl.isNotBlank()) foundLinks.add(dl)
        }

        doc.select("script").forEach { script ->
            val content = script.data()
            if ("Base64" in content && "decode" in content) {
                Regex("Base64\\.decode\\(['\"](.*?)['\"]\\)").findAll(content).forEach { match ->
                    try {
                        val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                        Regex("https?://[^\"]+").findAll(decoded).forEach { foundLinks.add(httpsify(it.value)) }
                    } catch (_: Exception) {}
                }
            }
        }

        val priorityHosts = listOf("streamwish","filemoon","dood","mixdrop","terabox","sbembed","vidhide","mirror","okru","uqload")
        val extracted = mutableListOf<ExtractorLink>()
        foundLinks.sortedBy { link ->
            val idx = priorityHosts.indexOfFirst { link.contains(it, true) }
            if (idx == -1) priorityHosts.size else idx
        }.map { link -> async {
            try { loadExtractor(link, data, subtitleCallback) { callback(it); extracted.add(it) } } catch (_: Exception) {}
        } }.awaitAll()

        linkCache[data] = now to extracted
        return@coroutineScope extracted.isNotEmpty()
    }
}
