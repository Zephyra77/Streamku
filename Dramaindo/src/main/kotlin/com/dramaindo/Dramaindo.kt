package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.json.JSONObject

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
        val synopsis = doc.selectFirst("div#sinopsis p, div.entry-content p, .desc p, .synopsis p")?.text()?.trim()
        val infoElems = doc.select("div#informasi.info ul li")

        val judul = getContent(infoElems, "Judul:")?.text()?.substringAfter("Judul:")?.trim() ?: title
        val genres = getContent(infoElems, "Genres:")?.select("a")?.map { it.text() } ?: emptyList()
        val year = getContent(infoElems, "Tahun:")?.selectFirst("a")?.text()?.toIntOrNull()
        val tipe = getContent(infoElems, "Tipe:")?.text()?.substringAfter("Tipe:")?.trim()

        val eps = parseEpisodesFromPage(doc)
        val typeLower = tipe?.lowercase()
        val forceMovie = typeLower?.contains("movie") == true || typeLower?.contains("film") == true || (eps.size == 1 && url.contains("?episode=1"))
        val isSeries = !forceMovie && (eps.size > 1 || url.contains("/series/"))

        val recommendations = doc.select("div.list-drama .style_post_1 article, div.idmuvi-rp ul li")
            .mapNotNull { it.toRecommendResult() }

        if (isSeries) {
            newTvSeriesLoadResponse(judul.ifBlank { title }, url, TvType.AsianDrama, eps) {
                posterUrl = poster
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
                plot = synopsis
                this.year = year
                this.tags = genres
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
            }
        } else {
            newMovieLoadResponse(judul.ifBlank { title }, url, TvType.Movie, url) {
                posterUrl = poster
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
                plot = synopsis
                this.year = year
                this.tags = genres
                addActors(doc.select("span[itemprop=actors] a").map { it.text() })
                this.recommendations = recommendations
                addTrailer(doc.selectFirst("a.gmr-trailer-popup")?.attr("href"))
            }
        }
    }

    private fun parseEpisodesFromPage(doc: Document): List<Episode> {
        return doc.select("ul.episode-list li a, .daftar-episode li a, div.list-episode-streaming ul.episode-list li a")
            .mapNotNull { a ->
                val name = a.text().trim().ifBlank { return@mapNotNull null }
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val epNum = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(fixUrl(href)).apply {
                    this.name = name
                    this.episode = epNum
                }
            }
    }

    override suspend fun loadLinks(
        str: String, 
        z: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val arrayList = mutableListOf<String>()
        val response = app.get(str, interceptor = interceptor)
        val doc = response.document

        doc.select("div.st-resolusi ul.resolusi-list li").forEach { attr ->
            val decodedData = base64Decode(attr.attr("data"))
            val jsonObject = JSONObject(decodedData)
            
            val subtitleUrl = jsonObject.optString("subtitle_url", "")
            if (subtitleUrl.isNotBlank()) {
                subtitleCallback(SubtitleFile("Indonesian", fixUrl(subtitleUrl)))
            }

            val links = jsonObject.optJSONArray("links")
            links?.forEach { linkElement ->
                val videoUrl = (linkElement as? JSONObject)?.optString("url")
                if (videoUrl?.isNotBlank() == true) {
                    arrayList.add(fixUrl(videoUrl))
                }
            }
        }

        doc.select("iframe[src]").mapNotNull { it.attr("src") }
            .forEach { arrayList.add(it) }

        val validUrls = arrayList.filter { it.startsWith("http") }
        validUrls.forEach { url ->
            val isM3u8 = url.endsWith(".m3u8")
            callback(
                ExtractorLink(
                    source = this@Dramaindo.name,
                    name = "${this@Dramaindo.name} 720p",
                    url = url,
                    referer = this@Dramaindo.mainUrl,
                    quality = getQualityFromName("720p"),
                    headers = mapOf("Referer" to this@Dramaindo.mainUrl),
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }

        validUrls.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3.title_post a, h2.entry-title a, h2 a, a") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href")
        val poster = selectFirst("div.thumbnail img, img")?.getImage()
        val isSeries = href.contains("/series/") || title.contains("Episode", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                posterUrl = poster
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val text = selectFirst("a > span.idmuvi-rp-title, .idmuvi-rp-title, a")?.text()?.trim() ?: return null
        val title = text.substringBefore("Season").substringBefore("Episode").substringBefore("Eps")
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.getImage()
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
    }

private fun Element.getImage(): String? {
    return attr("srcset").takeIf { it.isNotBlank() }?.split(",")
        ?.map { it.trim().split(" ") }
        ?.maxByOrNull { it.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0 }
        ?.firstOrNull()
        ?: attr("data-src").ifBlank { attr("src") }
}

private fun getContent(infoElems: Elements, label: String): Element? {
    return infoElems.find { it.text().contains(label, true) }?.select("span")
}

private fun fixUrl(url: String): String {
    return if (url.startsWith("http")) url else "$mainUrl$url"
}

private fun base64Decode(encoded: String): String {
    return String(java.util.Base64.getDecoder().decode(encoded))
}

private fun getQualityFromName(name: String): Int {
    return when (name) {
        "720p" -> 720
        "1080p" -> 1080
        else -> 480
    }
}
