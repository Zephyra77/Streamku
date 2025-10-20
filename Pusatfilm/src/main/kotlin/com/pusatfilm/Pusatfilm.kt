package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Pusatfilm : MainAPI() {
    override var mainUrl = "https://pusatfilm21.cam"
    override var name = "PusatFilm"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "/" to "Beranda",
        "/category/film-barat/" to "Film Barat",
        "/category/film-asia/" to "Film Asia",
        "/category/film-indonesia/" to "Film Indonesia",
        "/category/film-thailand/" to "Film Thailand"
    )

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst(".title")?.text() ?: "Tanpa Judul"
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return newMovieSearchResponse(title, mainUrl) {})
        val posterUrl = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")
        val isSeries = title.contains("Season", true) || title.contains("Episode", true)

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl?.let { poster = fixUrl(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl?.let { poster = fixUrl(it) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select(".movie-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".movie-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Tanpa Judul"
        val poster = doc.selectFirst(".poster img")?.attr("data-src")
        val desc = doc.selectFirst(".desc p")?.text()
        val tags = doc.select(".genxed a").map { it.text() }
        val actors = doc.select(".cast-list a").map { Actor(it.text()) }

        val episodes = doc.select(".server-item a").mapIndexed { index, ep ->
            newEpisode(ep.attr("href")) {
                this.name = ep.text().ifBlank { "Episode ${index + 1}" }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = tags
                this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = tags
                this.actors = actors
            }
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