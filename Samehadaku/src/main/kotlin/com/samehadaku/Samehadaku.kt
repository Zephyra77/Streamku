package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {

    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                t.contains("Completed", true) -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "daftar-anime-2/?title=&status=&type=&order=update" to "Terbaru",
        "daftar-anime-2/?title=&status=&type=TV&order=popular" to "TV Populer",
        "daftar-anime-2/?title=&status=&type=OVA&order=title" to "OVA",
        "daftar-anime-2/?title=&status=&type=Movie&order=title" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}", timeout = 50L).document
        val home = document.select("div.animposx, article.bs").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.next.page-numbers").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.attr("title").ifEmpty { aTag.text() }
        val href = fixUrl(aTag.attr("href")) ?: return null
        val posterUrl = fixUrl(aTag.selectFirst("img")?.attr("src")) ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.animposx, article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href") ?: return null
        val document = app.get(fixUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src") ?: ""
        val tags = document.select("div.genre-info > a").map { it.text() }
        val year = Regex("\\d{4}").find(document.text())?.value?.toIntOrNull()
        val status = getStatus(document.selectFirst("div.spe span:contains(Status)")?.ownText() ?: "")
        val type = getType(document.selectFirst("div.spe span:contains(Type)")?.ownText() ?: "")
        val trailer = fixUrl(document.selectFirst("div.trailer-anime iframe")?.attr("src")) ?: ""

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx a") ?: return@mapNotNull null
            val num = Regex("Episode\\s?(\\d+)").find(header.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link = fixUrl(header.attr("href")) ?: return@mapNotNull null
            newEpisode(link) { episode = num }
        }.reversed()

        val recommendations = document.select("aside#sidebar ul li").mapNotNull { it.toSearchResult() }
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image?.let { fixUrl(it) } ?: poster
            backgroundPosterUrl = tracker?.cover?.let { fixUrl(it) }
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = document.select("div.desc p").text().trim()
            this.tags = tags
            addTrailer(trailer)
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("div#downloadb li").apmap { el ->
            el.select("a").apmap {
                loadFixedExtractor(fixUrl(it.attr("href")) ?: return@apmap, el.select("strong").text(), "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(url: String, name: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun String.removeBloat(): String = this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)"), "").trim()

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("http")) url else mainUrl.trimEnd('/') + "/" + url.trimStart('/')
    }
}
