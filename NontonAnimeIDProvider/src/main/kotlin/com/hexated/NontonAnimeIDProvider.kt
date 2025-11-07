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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
        private fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        private fun getStatus(t: String): ShowStatus {
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
        val path = request.data.trimStart('/')
        val url = if (path.isBlank()) mainUrl else "$mainUrl/$path"
        val document = app.get(url).document
        val items = document.select(".animeseries, .result > ul > li, .posts > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("a") ?: return null
        val hrefRaw = a.attr("href").ifBlank { a.attr("abs:href") }
        val href = fixUrl(hrefRaw)
        val title = selectFirst(".title")?.text()
            ?: selectFirst("h2")?.text()
            ?: selectFirst(".name")?.text()
            ?: a.attr("title")
            ?: a.text()
        val posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())
        val tvType = getType(selectFirst(".typeseries")?.text() ?: "")
        return newAnimeSearchResponse(title.trim(), href, tvType) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val link = "$mainUrl/?s=$q"
        val document = app.get(link).document
        return document.select(".result > ul > li, .animeseries, .posts > li").mapNotNull {
            val title = it.selectFirst("h2, .title, .name")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(it.selectFirst(".boxinfores > span.typeseries, .typeseries")?.text() ?: "")
            val hrefRaw = it.selectFirst("a")?.attr("href") ?: it.selectFirst("a")?.attr("abs:href") ?: ""
            val href = fixUrl(hrefRaw)
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isEpisodePage = url.contains("-episode-", ignoreCase = true) || url.contains("/episode-", ignoreCase = true)
        val actualUrl = when {
            isEpisodePage -> url
            url.contains("/anime/", ignoreCase = true) -> url
            else -> {
                val tmp = try {
                    app.get(url).document.selectFirst(".nvs.nvsc a")?.attr("href")
                } catch (e: Exception) {
                    null
                }
                tmp ?: url
            }
        }

        val req = app.get(actualUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs, h1.entry-title, .title-post, .post-title")?.text()
            ?.removePrefix("Nonton Anime")?.removeSuffix("Sub Indo")?.trim() ?: ""

        val poster = document.selectFirst(".poster > img, .thumb img, .post-thumbnail img")?.getImageAttr()
        val tags = document.select(".tagline > a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = Regex("\\d{4}").find(document.selectFirst(".bottomtitle, .meta")?.text().orEmpty())?.value?.toIntOrNull()
        val status = getStatus(document.selectFirst("span.statusseries, .status")?.text().trim().orEmpty())
        val type = getType(document.selectFirst("span.typeseries, .type")?.text().trim().orEmpty())
        val rawScore = document.selectFirst("span.nilaiseries, .score")?.text()?.trim()?.toFloatOrNull()
        val score = rawScore?.let { Score.from(it, 10) }
        val description = document.selectFirst(".entry-content.seriesdesc, .description, .post-content")?.text()?.trim()
            ?: document.select("p").text().takeIf { it.isNotBlank() } ?: "Plot tidak ditemukan"
        val trailer = document.selectFirst("a.trailerbutton, a.trailer, .trailer a")?.attr("href")

        val episodes = try {
            val list = document.select("ul.misha_posts_wrap2 > li, .episodes li, .episode-list li, .misha_posts_wrap2 li, .episode-list-items > a")
            if (list.isNotEmpty()) {
                list.mapNotNull {
                    val a = it.selectFirst("a") ?: it
                    val ep = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val link = fixUrl(a.attr("href").ifBlank { a.attr("abs:href") }.ifBlank { a.attr("data-episode-url") })
                    newEpisode(link) { this.episode = ep }
                }.reversed()
            } else {
                if (document.select("button.buttfilter, .buttfilter").isNotEmpty()) {
                    val id = document.selectFirst("input[name=series_id]")?.attr("value").orEmpty()
                    val numEp = document.selectFirst(".latestepisode > a, .latest-episode a")?.text()?.replace(Regex("\\D"), "").orEmpty()
                    val ajax = try {
                        app.post(
                            url = "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "misha_number_of_results" to numEp,
                                "misha_order_by" to "date-DESC",
                                "action" to "mishafilter",
                                "series_id" to id
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                    val content = ajax?.let {
                        try {
                            it.parsed<EpResponse>().content
                        } catch (e: Exception) {
                            it.document.body().html()
                        }
                    } ?: ""
                    if (content.isNotBlank()) {
                        Jsoup.parse(content).select("li").mapNotNull {
                            val a = it.selectFirst("a") ?: return@mapNotNull null
                            val ep = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val link = fixUrl(a.attr("href"))
                            newEpisode(link) { this.episode = ep }
                        }.reversed()
                    } else {
                        val epNum = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)
                            ?: Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(req.document.selectFirst(".bottomtitle, #navigation-episode, .types")?.text().orEmpty())?.groupValues?.getOrNull(1)
                        val ep = epNum?.toIntOrNull()
                        val single = newEpisode(actualUrl) { this.episode = ep }
                        listOf(single)
                    }
                } else {
                    val epNum = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)
                        ?: Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(req.document.selectFirst(".bottomtitle, #navigation-episode, .types")?.text().orEmpty())?.groupValues?.getOrNull(1)
                    val ep = epNum?.toIntOrNull()
                    val single = newEpisode(actualUrl) { this.episode = ep }
                    listOf(single)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

        val recommendations = document.select(".result > li, .related-posts li, .recommendation li, .related li, .as-anime-grid a").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epTitle = it.selectFirst("h3, .title, .name")?.text() ?: a.text()
            val epPoster = it.selectFirst(".top > img, img")?.getImageAttr()
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
        val nonce = findNonceFromDocument(document)
        val serverElements = document.select(".container1 > ul > li:not(.boxtab), .tabs1.player li.tab-link, .player-servers li, .servers li, .episode-servers li")
        serverElements.map { element ->
            async {
                try {
                    val dataPost = element.attr("data-post")
                    val dataNume = element.attr("data-nume")
                    val serverName = element.attr("data-type").ifBlank { element.attr("data-server") }.lowercase()
                    val ajax = app.post(
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
                    )
                    val iframeSrc = ajax.document.selectFirst("iframe")?.attr("src")
                        ?: ajax.document.selectFirst("iframe")?.attr("data-src")
                        ?: ajax.document.selectFirst("iframe")?.attr("abs:src")
                    val resolvedIframe = when {
                        iframeSrc == null -> {
                            val body = ajax.document.body().html()
                            Regex("(https?://[^\"'>\\s]+)").find(body)?.value
                        }
                        iframeSrc.contains("/video-frame/") -> {
                            try {
                                app.get(iframeSrc).document.selectFirst("iframe")?.attr("data-src") ?: iframeSrc
                            } catch (e: Exception) {
                                iframeSrc
                            }
                        }
                        else -> iframeSrc
                    } ?: return@async
                    loadExtractor(resolvedIframe, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Exception) {
                }
            }
        }.awaitAll()
        try {
            val downloadLinks = document.select("#download_area .listlink a, #download_area a").mapNotNull { it.attr("href").takeIf { href -> href.isNotBlank() } }
            downloadLinks.forEach { dl ->
                try {
                    loadExtractor(dl, "$mainUrl/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        true
    }

    private fun findNonceFromDocument(document: org.jsoup.nodes.Document): String? {
        val byId = document.selectFirst("script#ajax_video-js-extra")?.attr("src")
        if (!byId.isNullOrBlank()) {
            val base64 = byId.substringAfter("base64,", "")
            if (base64.isNotBlank()) {
                try {
                    val decoded = String(Base64.getDecoder().decode(base64))
                    Regex("nonce\\W*[:=]\\W*['\\\"](\\S+?)['\\\"]").find(decoded)?.groupValues?.getOrNull(1)?.let { return it }
                } catch (_: Exception) {}
            }
        }
        val scriptText = document.select("script").mapNotNull { it.html().takeIf { txt -> txt.contains("base64") || txt.contains("nonce") } }.joinToString("\n")
        if (scriptText.isNotBlank()) {
            Regex("base64,([A-Za-z0-9+/=]+)").find(scriptText)?.groupValues?.getOrNull(1)?.let {
                try {
                    val decoded = String(Base64.getDecoder().decode(it))
                    Regex("nonce\\W*[:=]\\W*['\\\"](\\S+?)['\\\"]").find(decoded)?.groupValues?.getOrNull(1)?.let { n -> return n }
                } catch (_: Exception) {}
            }
            Regex("nonce\\W*[:=]\\W*['\\\"](\\S+?)['\\\"]").find(scriptText)?.groupValues?.getOrNull(1)?.let { return it }
        }
        document.selectFirst("input[name=nonce], input#nonce")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
        document.selectFirst("[data-nonce]")?.attr("data-nonce")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("data-original") -> attr("abs:data-original")
            else -> attr("abs:src")
        }.takeIf { it.isNotBlank() }
    }

    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )
}
