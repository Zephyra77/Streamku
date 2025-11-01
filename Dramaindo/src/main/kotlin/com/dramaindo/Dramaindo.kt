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
        val score = getContent(infoElems, "Skor:")?.text()?.substringAfter("Skor:")?.trim()?.let { it.toDoubleOrNull() }
        val director = getContent(infoElems, "Director:")?.text()?.substringAfter("Director:")?.trim()
        val tipe = getContent(infoElems, "Tipe:")?.text()?.substringAfter("Tipe:")?.trim()
        val negara = getContent(infoElems, "Negara:")?.selectFirst("a")?.text()
        val status = getContent(infoElems, "Status:")?.selectFirst("a")?.text()
        val ratingAge = getContent(infoElems, "Rating usia:")?.selectFirst("a")?.text()
        val originalNetwork = getContent(infoElems, "Original Network:")?.selectFirst("a")?.text()

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
                addScore(score?.let { "%.1f".format(it) })
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
                this.extra = mapOf(
                    "original_title" to (originalTitle ?: ""),
                    "director" to (director ?: ""),
                    "tipe" to (tipe ?: ""),
                    "negara" to (negara ?: ""),
                    "status" to (status ?: ""),
                    "rating_age" to (ratingAge ?: ""),
                    "network" to (originalNetwork ?: "")
                )
            }
        } else {
            newMovieLoadResponse(judul.ifBlank { title }, url, TvType.Movie, url) {
                posterUrl = poster
                plot = synopsis
                this.year = year
                this.tags = genres
                addScore(score?.let { "%.1f".format(it) })
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
                this.extra = mapOf(
                    "original_title" to (originalTitle ?: ""),
                    "director" to (director ?: ""),
                    "tipe" to (tipe ?: ""),
                    "negara" to (negara ?: ""),
                    "status" to (status ?: ""),
                    "rating_age" to (ratingAge ?: ""),
                    "network" to (originalNetwork ?: "")
                )
            }
        }
    }

    private fun parseEpisodesFromPage(doc: Document, pageUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val listSelectors = listOf(
            "ul.episode-list li",
            ".daftar-episode li",
            "div.list-episode-streaming ul.episode-list li",
            ".daftar-episode li a"
        )
        for (sel in listSelectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                for (li in els) {
                    val a = li.selectFirst("a") ?: li.selectFirst("a[href]") ?: continue
                    val name = a.text().trim()
                    if (name.isBlank()) continue
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                    val absolute = if (href.startsWith("?") || href.startsWith("#")) {
                        fixUrl(pageUrl + href)
                    } else {
                        fixUrl(href)
                    }
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
                    Regex("src\\s*=\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.getOrNull(1)?.let { src ->
                        return@mapNotNull src
                    }
                    Regex("https?://[\\w./\\-?=&%]+").find(decoded)?.groupValues?.getOrNull(0)?.let { link ->
                        return@mapNotNull link
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }.forEach { found.add(it) }

        doc.select(".link_download a, .download-box a, a[href]").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (href.contains("berkasdrive", true) || href.contains("mitedrive", true) || href.contains("drive", true) || href.contains("dl.berkasdrive", true) || href.contains("streaming", true)) href else null
        }.forEach { found.add(it) }

        val extracted = mutableListOf<ExtractorLink>()
        found.map { url ->
            async {
                runCatching {
                    loadExtractor(url, data, subtitleCallback) { link ->
                        callback(link)
                        extracted.add(link)
                    }
                }
            }
        }.awaitAll()

        extracted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.title_post a, h2.entry-title a, h2 a, a")
        val t = titleEl?.text()?.trim() ?: return null
        val href = titleEl.attr("href")
        val poster = selectFirst("div.thumbnail img, img")?.getImage()
        val score = Regex("Score:\\s*([^\\s]+)").find(select("div.info li").text())?.groupValues?.getOrNull(1)
        return if (href.contains("/tv/") || selectFirst(".title_episode_2") != null || t.contains("Episode", true).not()) {
            newTvSeriesSearchResponse(t.substringBefore("Season").substringBefore("Episode").substringBefore("Eps"), href, TvType.AsianDrama) {
                posterUrl = poster
                score?.let { addScore(Score.from10(it)) }
                posterHeaders = interceptor.getCookieHeaders(mainUrl)
            }
        } else {
            newMovieSearchResponse(t, href, TvType.Movie) {
                posterUrl = poster
                score?.let { addScore(Score.from10(it)) }
                posterHeaders = interceptor.getCookieHeaders(mainUrl)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val t = selectFirst("a > span.idmuvi-rp-title, .idmuvi-rp-title, a")?.text()?.trim() ?: return null
        val title = t.substringBefore("Season").substringBefore("Episode").substringBefore("Eps")
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.getImage()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
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
        return try { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) } catch (_: Throwable) { "" }
    }
}
