package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class Dramaindo : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "Dramaindo"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val interceptor by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "/negara/korea-selatan" to "Drakor",
        "/negara/china" to "Drachin",
        "/type/movie" to "Movie",
        "/status-drama/ongoing" to "Drama Ongoing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}"
        val res = app.get(url, interceptor = interceptor)
        val items = res.document.select("div.post_index div.style_post_1 article, .style_post_1 article")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/?s=$query", interceptor = interceptor)
        return res.document.select("div.post_index div.style_post_1 article, .style_post_1 article")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val res = app.get(url, interceptor = interceptor)
        val doc = res.document

        val title = doc.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("div.thumbnail_single img, .thumbnail img, figure img, .wp-post-image")?.getImage()
        val synopsis = doc.selectFirst("div#sinopsis.synopsis p, div.entry-content p, .desc p, .synopsis p")?.text()?.trim()
        val infoElems = doc.select("div#informasi.info ul li")

        val judul = getContent(infoElems, "Judul:")?.text()?.substringAfter("Judul:")?.trim() ?: title
        val originalTitle = getContent(infoElems, "Judul Asli:")?.selectFirst("a, span")?.text()
            ?: getContent(infoElems, "Judul Asli:")?.text()?.substringAfter("Judul Asli:")?.trim()
        val genres = getContent(infoElems, "Genres:")?.select("a")?.map { it.text() } ?: emptyList()
        val year = getContent(infoElems, "Tahun:")?.selectFirst("a")?.text()?.toIntOrNull()
        val score = getContent(infoElems, "Skor:")?.text()?.substringAfter("Skor:")?.trim()
        val tipe = getContent(infoElems, "Tipe:")?.text()?.substringAfter("Tipe:")?.trim()
        val eps = parseEpisodesFromPage(doc, url)
        val isSeries = eps.isNotEmpty() || url.contains("/series/") || tipe?.contains("Drama", true) == true

        val recommendations = doc.select("div.list-drama .style_post_1 article, div.idmuvi-rp ul li")
            .mapNotNull { it.toRecommendResult() }

        if (isSeries) {
            newTvSeriesLoadResponse(judul.ifBlank { title }, url, TvType.AsianDrama, eps) {
                posterUrl = poster
                plot = synopsis
                this.year = year
                this.tags = genres
                if (!score.isNullOrBlank()) addScore(score, maxValue = 10)
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
            }
        } else {
            newMovieLoadResponse(judul.ifBlank { title }, url, TvType.Movie, url) {
                posterUrl = poster
                plot = synopsis
                this.year = year
                this.tags = genres
                if (!score.isNullOrBlank()) addScore(score, maxValue = 10)
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
            }
        }
    }

    private fun parseEpisodesFromPage(doc: Document, pageUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val listSelectors = listOf(
            "ul.episode-list li a",
            ".daftar-episode li a",
            "div.list-episode-streaming ul.episode-list li a"
        )
        for (sel in listSelectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                for (a in els) {
                    val name = a.text().trim()
                    if (name.isBlank()) continue
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                    val absolute = fixUrl(href)
                    val epNum = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    eps.add(newEpisode(absolute).apply {
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
        val found = mutableSetOf<String>()
        val res = app.get(data, interceptor = interceptor)
        val doc = res.document

        doc.select("iframe").mapNotNull { it.attr("src")?.takeIf { it.isNotBlank() } }
            .forEach { found.add(it) }

        doc.select(".streaming-box, .streaming_load").mapNotNull {
            try {
                val b64 = it.attr("data").takeIf { s -> s.isNotBlank() }
                if (!b64.isNullOrBlank()) {
                    val decoded = base64Decode(b64)
                    Regex("src\\s*=\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.getOrNull(1)
                        ?: Regex("https?://[\\w./\\-?=&%]+").find(decoded)?.groupValues?.getOrNull(0)
                } else null
            } catch (e: Exception) {
                null
            }
        }.forEach { found.add(it) }

        doc.select(".link_download a, .download-box a, a[href]").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (href.contains("berkasdrive", true) || href.contains("mitedrive", true) ||
                href.contains("drive", true) || href.contains("streaming", true)
            ) href else null
        }.forEach { found.add(it) }

        found.map { url ->
            async {
                runCatching {
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
        }.awaitAll()

        found.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.title_post a, h2.entry-title a, h2 a, a") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href")
        val poster = selectFirst("div.thumbnail img, img")?.getImage()
        val score = Regex("Score:\\s*([^\\s]+)").find(select("div.info li").text())?.groupValues?.getOrNull(1)
        val isSeries = href.contains("/series/") || title.contains("Episode", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                posterUrl = poster
                if (!score.isNullOrBlank()) addScore(score, maxValue = 10)
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (!score.isNullOrBlank()) addScore(score, maxValue = 10)
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val t = selectFirst("a > span.idmuvi-rp-title, .idmuvi-rp-title, a")?.text()?.trim() ?: return null
        val title = t.substringBefore("Season").substringBefore("Episode").substringBefore("Eps")
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.getImage()
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
    }

    private fun Element.getImage(): String? {
        return attr("srcset")
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().split("\\s+".toRegex()) }
            ?.maxByOrNull { it.getOrNull(1)?.replace("w", "")?.toIntOrNull() ?: 0 }
            ?.firstOrNull()
            ?: attr("data-src")
                .ifBlank { attr("data-lazy-src") }
                .ifBlank { attr("src") }
                ?.replace(Regex("-\\d+x\\d+"), "")
    }

    private fun getContent(elements: Elements, text: String): Element? {
        return elements.firstOrNull { el -> el.selectFirst("strong")?.text()?.trim() == text }
    }

    private fun base64Decode(s: String): String {
        return try {
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
        } catch (_: Throwable) {
            ""
        }
    }
}
