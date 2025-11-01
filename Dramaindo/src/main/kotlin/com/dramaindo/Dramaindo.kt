package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Dramaindo : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "Dramaindo"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "/negara/korea-selatan" to "Drakor",
        "/negara/china" to "Drachin",
        "/type/movie" to "Movie",
        "/status-drama/ongoing" to "Drama Ongoing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$mainUrl/${request.data.format(page)}")
        val items = res.document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return res.document.select("article.item, article[itemscope]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1.title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("figure img, .wp-post-image, .poster img, .thumb img")?.getImage()
        val desc = doc.selectFirst("div.entry-content p, div[itemprop=description] p, .synopsis p")?.text()?.trim()
        val rating = doc.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val year = doc.select("div:contains(Year:) a").lastOrNull()?.text()?.toIntOrNull()
        val tags = doc.select("div:contains(Genre:) a").map { it.text() }
        val actors = doc.select("span[itemprop=actors] a").map { it.text() }
        val trailer = doc.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val recommendations = doc.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }
        val eps = parseEpisodes(doc)
        val isSeries = eps.isNotEmpty() || url.contains("/tv/")

        if (isSeries) {
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
        val eps = mutableListOf<Episode>()
        val selectors = listOf(
            "div.gmr-listseries a",
            "div.vid-episodes a",
            "ul.episodios li a",
            "div.episodios a",
            "div.dzdesu ul li a",
            "div.box a",
            "div.box p:containsOwn(Episode) + a"
        )
        for (sel in selectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                for (a in els) {
                    val name = a.text().trim()
                    if (name.isBlank() || name.contains("Segera", true) || name.contains("Coming Soon", true)) continue
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
        val foundLinks = mutableSetOf<String>()
        val doc = app.get(data).document

        doc.select("iframe").mapNotNull { it.attr("src")?.takeIf { it.isNotBlank() } }.forEach { foundLinks.add(it) }
        doc.select("a").mapNotNull { it.attr("href")?.takeIf { it.isNotBlank() && it.contains("drive", true) } }.forEach { foundLinks.add(it) }

        val extracted = mutableListOf<ExtractorLink>()

        foundLinks.map { url ->
            async {
                runCatching {
                    loadExtractor(url, data, subtitleCallback) { link ->
                        val extractorLink = ExtractorLink(
                            source = link.source,
                            name = link.name,
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            headers = link.headers,
                            extractorData = link.extractorData,
                            type = link.type
                        )
                        callback(extractorLink)
                        extracted.add(link)
                    }
                }
            }
        }.awaitAll()

        extracted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleRaw = selectFirst("h2.entry-title a, h2 a")?.text()?.trim() ?: return null
        val title = titleRaw.substringBefore("Season").substringBefore("Episode").substringBefore("Eps")
        val a = selectFirst("a") ?: return null
        val href = a.attr("href")
        val poster = selectFirst("img")?.getImage()
        return if (href.contains("/tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val t = selectFirst("a > span.idmuvi-rp-title, .idmuvi-rp-title")?.text()?.trim() ?: return null
        val title = t.substringBefore("Season").substringBefore("Episode").substringBefore("Eps")
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.getImage()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    private fun Element.getImage(): String? {
        return attr("data-src")
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("src") }
            ?.fixImageQuality()
    }

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("-\\d*x\\d*"), "")
}
