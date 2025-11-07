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
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing-list/" to "Ongoing List",
        "popular-series/" to "Popular Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val home = document.select(".animeseries").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = fixUrl(selectFirst("a")!!.attr("href"))
        val title = selectFirst(".title")?.text() ?: selectFirst("h2")?.text() ?: ""
        val posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document
        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: ""
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(it.selectFirst(".boxinfores > span.typeseries")?.text() ?: "")
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val actualUrl = if (url.contains("/anime/")) url
        else app.get(url).document.selectFirst(".nvs.nvsc a")?.attr("href")

        val req = app.get(actualUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs")?.text()
            ?.removePrefix("Nonton Anime")?.removeSuffix("Sub Indo")?.trim() ?: ""

        val poster = document.selectFirst(".poster > img")?.getImageAttr()
        val tags = document.select(".tagline > a").map { it.text() }
        val year = Regex("\\d{4}").find(document.select(".bottomtitle").text())?.value?.toIntOrNull()
        val status = getStatus(document.select("span.statusseries").text().trim())
        val type = getType(document.select("span.typeseries").text().trim())
        val rawScore = document.select("span.nilaiseries").text().trim().toFloatOrNull()
        val score = rawScore?.let { Score.from(it, 10) }
        val description = document.selectFirst(".entry-content.seriesdesc")?.text()?.trim()
            ?: document.select("p").text().takeIf { it.isNotBlank() } ?: "Plot tidak ditemukan"
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = if (document.select("button.buttfilter").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp = document.selectFirst(".latestepisode > a")?.text()
                ?.replace(Regex("\\D"), "").orEmpty()
            Jsoup.parse(
                app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "misha_number_of_results" to numEp,
                        "misha_order_by" to "date-DESC",
                        "action" to "mishafilter",
                        "series_id" to id
                    )
                ).parsed<EpResponse>().content
            ).select("li").map {
                val ep = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1)?.toIntOrNull()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                newEpisode(link) { this.episode = ep }
            }.reversed()
        } else {
            document.select("ul.misha_posts_wrap2 > li").map {
                val ep = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1)?.toIntOrNull()
                val link = it.select("a").attr("href")
                newEpisode(link) { this.episode = ep }
            }.reversed()
        }

        val recommendations = document.select(".result > li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val epPoster = it.selectFirst(".top > img")?.getImageAttr()
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
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
            .substringAfter("base64,")
            .let {
                if (it.isBlank()) null else try {
                    val decoded = String(Base64.getDecoder().decode(it))
                    Regex("nonce\":\"(\\S+?)\"").find(decoded)?.groupValues?.get(1)
                } catch (e: Exception) {
                    null
                }
            }
        document.select(".container1 > ul > li:not(.boxtab)").map { element ->
            async {
                val dataPost = element.attr("data-post")
                val dataNume = element.attr("data-nume")
                val serverName = element.attr("data-type").lowercase()
                val iframe = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "nonce" to "$nonce",
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
                loadExtractor(iframe ?: return@async, "$mainUrl/", subtitleCallback, callback)
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
