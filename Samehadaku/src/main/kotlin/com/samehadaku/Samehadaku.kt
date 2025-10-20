package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://samehadaku.lol"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "/anime-terbaru/page/%d/" to "Anime Terbaru",
        "/anime-ongoing/page/%d/" to "Anime Ongoing",
        "/anime-completed/page/%d/" to "Anime Completed",
        "/anime-movie/page/%d/" to "Anime Movie"
    )

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst(".animposx .title")?.text()?.trim() ?: "Tanpa Judul"
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return newAnimeSearchResponse(title, mainUrl) {})
        val posterUrl = this.selectFirst("img")?.attr("src")
        val type = when {
            title.contains("Movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            posterUrl?.let { poster = it }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val doc = app.get(url).document
        val items = doc.select(".post-show").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".post-show").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Tanpa Judul"
        val poster = doc.selectFirst(".thumb img")?.attr("src")
        val description = doc.selectFirst(".desc p")?.text()
        val genres = doc.select(".genres a").map { it.text() }
        val episodes = doc.select(".epslist a").mapIndexed { index, ep ->
            newEpisode(fixUrl(ep.attr("href"))) {
                name = ep.text().ifBlank { "Episode ${index + 1}" }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false
        loadExtractor(fixUrl(iframe), mainUrl, callback)
        return true
    }
}