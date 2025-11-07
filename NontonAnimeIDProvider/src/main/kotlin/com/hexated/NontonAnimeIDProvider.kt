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
        val home = document.select(".animeseries, .gacha-grid .gacha-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = fixUrl(selectFirst("a")!!.attr("href"))
        val title = selectFirst(".title")?.text() ?: selectFirst("h2")?.text() ?: ""
        val posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
            posterHeaders = MapsKt.toMap((getInterceptor().getCookieHeaders(String.valueOf(mainUrl))))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document
        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: it.selectFirst(".title")?.text()?.trim() ?: ""
            val poster = it.selectFirst("img")?.getImageAttr()
            val typeEl = it.selectFirst(".boxinfores > span.typeseries")?.text() ?: ""
            val tvType = getType(typeEl)
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
                posterHeaders = MapsKt.toMap((getInterceptor().getCookieHeaders(String.valueOf(mainUrl))))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val actualUrl =
            if (url.contains("/anime/")) url else app.get(url).document.selectFirst(".nvs.nvsc a")?.attr("href") ?: url
        val req = app.get(actualUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = (document.selectFirst("h1.entry-title.cs")?.text()
            ?: document.selectFirst(".entry-title")?.text() ?: "")
            .removeSurrounding("Nonton Anime", "Sub Indo").trim()

        val poster = document.selectFirst(".poster > img")?.getImageAttr()
            ?: document.selectFirst(".thumb img")?.getImageAttr()
        val tags = (document.select(".tagline > a").map { it.text() } + document.select(".genres a").map { it.text() }).filter { it.isNotBlank() }
        val year = Regex(", (\\d{4})").find(document.select(".bottomtitle > span:nth-child(5)")?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = getStatus(document.select("span.statusseries")?.text()?.trim().orEmpty())
        val type = getType(document.select("span.typeseries")?.text()?.trim().orEmpty())
        val rawScore = document.select("span.nilaiseries")?.text()?.trim()?.toFloatOrNull()
        val score = rawScore?.let { Score.from(it, 10) }

        val description = listOf(
            document.selectFirst(".entry-content.seriesdesc > p")?.text(),
            document.selectFirst(".seriesdesc")?.text(),
            document.selectFirst(".desc")?.text(),
            document.selectFirst(".synopsis")?.text(),
            document.selectFirst(".entry-content > p")?.text(),
            document.selectFirst(".summary")?.text()
        ).filterNotNull().joinToString("\n\n").trim()

        val trailer = document.selectFirst("a.trailerbutton")?.attr("href") ?: document.selectFirst(".trailer a")?.attr("href")

        var episodes = listOf<Episode>()

        val hasFilter = document.select("button.buttfilter, button[data-series], button.filter-button").isNotEmpty()
        if (hasFilter) {
            val idCandidate = document.selectFirst("input[name=series_id]")?.attr("value")
                .takeIf { !it.isNullOrEmpty() }
                ?: document.selectFirst("button.buttfilter")?.attr("data-series")
                ?: document.selectFirst("button[data-series]")?.attr("data-series")
                ?: document.selectFirst("input#series_id")?.attr("value")
                ?: Regex("/(\\d+)(?:/|\$)").find(actualUrl ?: "")?.groupValues?.getOrNull(1)

            val numEpCandidate = document.selectFirst(".latestepisode > a")?.text()
                ?.replace(Regex("\\D"), "")
                ?.takeIf { it.isNotEmpty() }
                ?: document.select("ul.misha_posts_wrap2 > li").size.toString()
                ?: "9999"

            if (!idCandidate.isNullOrEmpty()) {
                val postData = mapOf(
                    "misha_number_of_results" to numEpCandidate,
                    "misha_order_by" to "date-DESC",
                    "action" to "mishafilter",
                    "series_id" to idCandidate
                )

                val ajaxResp = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = postData,
                    referer = actualUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<EpResponse>()

                val contentHtml = ajaxResp.content
                if (contentHtml.isNullOrBlank()) {
                    episodes = fallbackEpisodeParse(document)
                } else {
                    val doc2 = Jsoup.parse(contentHtml)
                    episodes = doc2.select("li").mapNotNull {
                        val epText = it.selectFirst("a")?.text().orEmpty()
                        val epNum = Regex("Episode\\s?(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val rawLink = it.selectFirst("a")?.attr("href").orEmpty()
                        val link = fixUrl(rawLink)
                        newEpisode(link) { this.episode = epNum }
                    }.filterNotNull().reversed()
                }
            } else {
                episodes = fallbackEpisodeParse(document)
            }
        } else {
            episodes = fallbackEpisodeParse(document)
        }

        val recommendations = document.select(".result > li, .related > li, .recommendation-list li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst("h3")?.text() ?: it.selectFirst(".title")?.text() ?: ""
            val epPoster = it.selectFirst(".top > img")?.getImageAttr() ?: it.selectFirst("img")?.getImageAttr()
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

    private fun fallbackEpisodeParse(document: org.jsoup.nodes.Document): List<Episode> {
        val sels = listOf(
            "ul.misha_posts_wrap2 > li",
            ".misha_posts_wrap2 li",
            ".eps-list li",
            ".episode-list li",
            ".episodelist li",
            ".list-episode li",
            ".episodes li",
            ".post-episodes li",
            ".animeseries li"
        )
        for (s in sels) {
            val nodes = document.select(s)
            if (nodes.isNotEmpty()) {
                return nodes.mapNotNull {
                    val epText = it.selectFirst("a")?.text().orEmpty()
                    val epNum = Regex("Episode\\s?(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val rawLink = it.selectFirst("a")?.attr("href").orEmpty()
                    val link = fixUrl(rawLink)
                    newEpisode(link) { this.episode = epNum }
                }.filterNotNull().reversed()
            }
        }
        return emptyList()
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
            .let { s ->
                if (s.isNullOrEmpty()) null
                else Regex("nonce\":\"(\\S+?)\"").find(base64Decode(s))?.groupValues?.getOrNull(1)
            }

        document.select(".container1 > ul > li:not(.boxtab), .container1 ul.tabs1.player li:not(.boxtab)").map { element ->
            async {
                val dataPost = element.attr("data-post")
                val dataNume = element.attr("data-nume")
                val serverName = element.attr("data-type").lowercase()
                val iframe = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "nonce" to "${nonce ?: ""}",
                        "serverName" to serverName,
                        "nume" to dataNume,
                        "post" to dataPost
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document.selectFirst("iframe")?.attr("src")?.let {
                    if (it.contains("/video-frame/")) app.get(it).document.selectFirst("iframe")?.attr("data-src") else it
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
