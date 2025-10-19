package com.nimeindo

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Nimeindo : MainAPI() {
    override var mainUrl = "https://nimeindo.web.id"
    override var name = "Nimeindo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title")
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("data-src")
            ?: this.select("div.bsx > a img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
        var poster = document.select("div.ime > img").attr("data-src")
            ?: document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text() ?: ""
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            val doc = app.get(epPage).document
            val episodes = doc.select("div.episodelist > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                val episode = info.select("a span").text().substringAfter("Episode").trim().let { "Episode $it" }
                val posterr = info.selectFirst("a img")?.attr("data-src") ?: info.selectFirst("a img")?.attr("src") ?: ""
                newEpisode(href1) {
                    this.name = episode
                    this.posterUrl = fixUrl(posterr)
                }
            }
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
            }
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value")
            val decoded = base64Decode(base64)
            val doc = Jsoup.parse(decoded)
            val href = doc.select("iframe").attr("src")
            val url = fixUrl(href)
            loadExtractor(url, subtitleCallback, callback)
        }
        return true
    }
}
