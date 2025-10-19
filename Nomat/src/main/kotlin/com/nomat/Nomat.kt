package com.nomat

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

fun base64Decode(encoded: String): String {
    return String(Base64.decode(encoded, Base64.DEFAULT))
}

fun String.httpsify(): String = if (this.startsWith("http")) this else "https:$this"

class Nomat : MainAPI() {
    override var mainUrl = "https://nomat.site"
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "slug/film-terbaru" to "Film Terbaru",
        "slug/film-terfavorit" to "Film Terfavorit",
        "slug/film-box-office" to "Film Box Office"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/$page/"
        val document = app.get(url).document
        val home = document.select("a:has(div.item-content)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val style = this.selectFirst("div.poster")?.attr("style") ?: ""
        val posterUrl = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
        val quality = this.selectFirst("div.qual")?.text()?.trim()
        return if (title.contains("Season", true) || title.contains("Episode", true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = app.get(url).document
        return document.select("div.body a").mapNotNull { el ->
            try {
                val href = fixUrl(el.attr("href"))
                val title = el.selectFirst("div.title")?.text()?.trim() ?: el.text().trim()
                val style = el.selectFirst("div.poster")?.attr("style") ?: ""
                val poster = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.getOrNull(1)
                val isSeries = title.contains("Season", true) || title.contains("Episode", true)
                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.video-title h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.video-poster")?.attr("style")?.substringAfter("url('")?.substringBefore("')")?.httpsify()
        val tags = document.select("div.video-genre a").map { it.text() }
        val year = document.select("div.video-duration a[href*=/category/year/]")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.video-synopsis")?.text()?.trim()
        val trailer = document.selectFirst("div.video-trailer iframe")?.attr("src")
        val rating = document.selectFirst("div.rtg")?.text()?.trim()
        val actors = document.select("div.video-actor a").map { it.text() }
        val recommendations = document.select("div.section .item-content").mapNotNull { it.toRecommendResult() }

        val episodeLinks = document.select("div.video-episodes a")
        val isSeries = episodeLinks.isNotEmpty() || url.contains("/serial-tv/")

        return if (isSeries) {
            val episodes = episodeLinks.map { eps ->
                val href = fixUrl(eps.attr("href"))
                val name = eps.text()
                val episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
                val season = Regex("Season\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = episode
                    this.season = season
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
                addScore(rating ?: "")
            }
        } else {
            val playUrl = document.selectFirst("div.video-wrapper a[href*='nontonhemat.link']")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
                addScore(rating ?: "")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, referer = mainUrl, timeout = 100L).document
            document.select("div.server-item").forEach { el ->
                val encoded = el.attr("data-url")
                if (encoded.isNotBlank()) {
                    try {
                        val decoded = base64Decode(encoded)
                        loadExtractor(decoded, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val href = fixUrl(this.attr("href"))
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val style = this.selectFirst("div.poster")?.attr("style") ?: ""
        val posterUrl = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
}
