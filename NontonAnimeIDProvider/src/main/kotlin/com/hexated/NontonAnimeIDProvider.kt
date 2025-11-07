package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s7.nontonanimeid.boats"
    override var name = "NontonAnimeID"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Finished", true) -> ShowStatus.Completed
                t.contains("Airing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf("" to "Terbaru", "ongoing-list/" to "Ongoing", "popular-series/" to "Populer")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val home = document.select(".animeseries").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(".title")?.text()?.trim() ?: selectFirst("h2")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document
        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(it.selectFirst(".boxinfores > span.typeseries")?.text() ?: "")
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val req = app.get(url)
        val document = req.document
        mainUrl = getBaseUrl(req.url)

        val title = document.selectFirst("h1.entry-title.cs")?.text()?.replace("Nonton Anime", "")?.replace("Sub Indo", "")?.trim().orEmpty()
        val poster = document.selectFirst(".poster img")?.getImageAttr()
        val tags = document.select(".tagline a").map { it.text() }
        val year = Regex("\\d{4}").find(document.select(".bottomtitle").text())?.value?.toIntOrNull()
        val status = getStatus(document.select("span.statusseries").text())
        val type = getType(document.select("span.typeseries").text())
        val score = document.select("span.nilaiseries").text().toFloatOrNull()?.let { Score.from(it, 10) }
        val description = document.selectFirst(".entry-content.seriesdesc")?.text()
            ?: document.select("p").text().takeIf { it.isNotBlank() } ?: "Tidak ada deskripsi."
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodeElements = document.select(".epsleft a, ul.misha_posts_wrap2 li a, div.episode-list-items a.episode-item")
        val episodes = episodeElements.mapIndexedNotNull { index, el ->
            val num = Regex("Episode\\s?(\\d+)").find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)
            val link = el.attr("href").ifEmpty { return@mapIndexedNotNull null }
            EpisodeData(num, fixUrl(link))
        }.distinctBy { it.number }.sortedBy { it.number }.map { ep ->
            newEpisode(ep.link) { this.episode = ep.number }
        }

        val recommendations = document.select(".result li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val epPoster = it.selectFirst("img")?.getImageAttr()
            newAnimeSearchResponse(epTitle, fixUrl(epHref), TvType.Anime) {
                posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.score = score
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val document = app.get(data).document
        document.select("#download_area .listlink a").map { element ->
            async {
                val url = element.attr("href")
                val quality = element.text()
                if (url.isNotBlank()) {
                    loadExtractor(url, mainUrl, subtitleCallback) { link ->
                        callback(link.copy(name = "$quality (${link.name})"))
                    }
                }
            }
        }.awaitAll()
        true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private data class EpisodeData(val number: Int, val link: String)
}
