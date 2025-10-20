package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://samehadaku.cam"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "anime-terbaru" to "Anime Terbaru",
        "anime-ongoing" to "Ongoing",
        "anime-completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val results = document.select("div.animposx").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, results),
            hasNext = document.select("a.next.page-numbers").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 > a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.animposx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("div.thumb > img")?.attr("src"))
        val description = document.selectFirst("div.desc p")?.text()
        val trailer = document.selectFirst("a.trailer-popup")?.attr("href")
        val episodes = document.select("ul.episodios li a").mapNotNull { ep ->
            val href = fixUrl(ep.attr("href"))
            val name = ep.text()
            newEpisode(href) { this.name = name }
        }

        return newAnimeLoadResponse(title, url, this.name, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addTrailer(trailer)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.attr("src") ?: return false
        loadExtractor(fixUrl(iframe), mainUrl, subtitleCallback, callback)
        return true
    }
}