package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s7.nontonanimeid.boats"
    override var name = "NontonAnimeID"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

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

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "ongoing-list/" to "Ongoing",
        "popular-series/" to "Populer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val home = document.select(".animeseries").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(".title")?.text()?.trim()
            ?: selectFirst("h2")?.text()?.trim() ?: return null
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

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst(".anime-card__cover img")?.getImageAttr()
        val tags = document.select(".anime-card__tags a").map { it.text() }
        val year = Regex("\\d{4}").find(document.select(".anime-card__meta").text())?.value?.toIntOrNull()
        val status = getStatus(document.select(".anime-card__status").text())
        val type = getType(document.select(".anime-card__type").text())
        val score = document.select(".anime-card__score").text().toFloatOrNull()?.let { Score.from(it, 10) }
        val description = document.selectFirst(".anime-card__description")?.text()
            ?: document.select("p").text().takeIf { it.isNotBlank() } ?: "Tidak ada deskripsi."
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = document.select("div.episode-list-items a.episode-item").mapIndexed { index, el ->
            val name = el.selectFirst(".ep-title")?.text() ?: "Episode ${index + 1}"
            val link = fixUrl(el.attr("href"))
            val date = el.selectFirst(".ep-date")?.text()
            newEpisode(link) {
                this.name = name
                this.date = date
                this.episode = index + 1
            }
        }

        val recommendations = document.select(".anime-card__related a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.selectFirst(".title")?.text() ?: return@mapNotNull null
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
        val nonce = document.select("script#ajax_video-js-extra").attr("src")
            ?.substringAfter("base64,")?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(it))
                    Regex("nonce\":\"(\\S+?)\"").find(decoded)?.groupValues?.get(1)
                }.getOrNull()
            }

        document.select(".container1 > ul > li:not(.boxtab)").map { element ->
            async {
                val dataPost = element.attr("data-post")
                val dataNume = element.attr("data-nume")
                val serverName = element.attr("data-type").orEmpty().lowercase()
                val iframe = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "player_ajax",
                            "nonce" to (nonce ?: ""),
                            "serverName" to serverName,
                            "nume" to dataNume,
                            "post" to dataPost
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).document.selectFirst("iframe")?.attr("src")?.let {
                        if (it.contains("/video-frame/"))
                            app.get(it).document.select("iframe").attr("data-src")
                        else it
                    }
                }.getOrNull()
                if (!iframe.isNullOrBlank()) loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
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

    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )
}
