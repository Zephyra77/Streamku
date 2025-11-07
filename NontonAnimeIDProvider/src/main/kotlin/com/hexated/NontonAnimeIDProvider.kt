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
                t.contains("tv", true) -> TvType.Anime
                t.contains("movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("finished", true) -> ShowStatus.Completed
                t.contains("ongoing", true) || t.contains("currently", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing-list/" to "Ongoing List",
        "popular-series/" to "Popular Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val d = app.get(url).document
        val items = buildList {
            addAll(d.select(".animeseries").mapNotNull { it.toSearchResultBySeriesCard() })
            addAll(d.select(".as-anime-card").mapNotNull { it.toSearchResultByAsCard() })
            addAll(d.select(".result > ul > li").mapNotNull { it.toSearchResultByResultLi() })
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResultBySeriesCard(): AnimeSearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst(".title")?.text()?.trim().orEmpty().ifEmpty {
            a.attr("title").ifEmpty { a.text().trim() }
        }
        val poster = selectFirst("img")?.getImageAttr()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    private fun Element.toSearchResultByAsCard(): AnimeSearchResponse? {
        val a = this
        val href = fixUrl(a.attr("href"))
        val title = selectFirst(".as-anime-title")?.text()?.trim().orEmpty().ifEmpty {
            a.attr("title").ifEmpty { a.text().trim() }
        }
        val poster = selectFirst("img")?.getImageAttr()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    private fun Element.toSearchResultByResultLi(): AnimeSearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = selectFirst("h2, h3, .title")?.text()?.trim().orEmpty().ifEmpty {
            a.attr("title").ifEmpty { a.text().trim() }
        }
        val poster = selectFirst("img")?.getImageAttr()
        val tvTypeText = selectFirst(".boxinfores > span.typeseries")?.text().orEmpty()
        val tvType = getType(tvTypeText)
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val link = "$mainUrl/?s=$q"
        val d = app.get(link).document
        val list = buildList {
            addAll(d.select(".result > ul > li").mapNotNull { it.toSearchResultByResultLi() })
            addAll(d.select(".as-anime-card").mapNotNull { it.toSearchResultByAsCard() })
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        } ?: return null

        val req = app.get(seriesUrl)
        mainUrl = getBaseUrl(req.url)
        val d = req.document

        val rawTitle = d.selectFirst("h1.entry-title.cs")?.text().orEmpty()
        val title = rawTitle.replace(Regex("^Nonton\\s+Anime\\s+"), "")
            .replace(Regex("\\s+Sub\\s+Indo$"), "")
            .trim()

        val poster = d.selectFirst(".poster img")?.getImageAttr()
        val tags = d.select(".tagline > a").map { it.text() }
        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(d.select(".bottomtitle > span").joinToString(" "))?.value?.toIntOrNull()

        val statusText = d.select("span.statusseries").text().trim()
        val status = getStatus(statusText)

        val typeText = d.select("span.typeseries").text().trim()
        val type = getType(typeText)

        val rawScore = d.select("span.nilaiseries").text().trim().toFloatOrNull()
        val score = rawScore?.let { Score.from(it, 10) }

        val description = d.select(".entry-content.seriesdesc > p").joinToString("\n") { it.text().trim() }.trim()
        val trailer = d.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = if (d.select("button.buttfilter").isNotEmpty()) {
            val id = d.select("input[name=series_id]").attr("value")
            val numEp = d.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "").orEmpty().ifEmpty { "999" }
            val ajaxHtml = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "misha_number_of_results" to numEp,
                    "misha_order_by" to "date-DESC",
                    "action" to "mishafilter",
                    "series_id" to id
                )
            ).parsed<EpResponse>().content
            Jsoup.parse(ajaxHtml).select("li").mapNotNull {
                val a = it.selectFirst("a") ?: return@mapNotNull null
                val epStr = Regex("Episode\\s?(\\d+)").find(a.text())?.groupValues?.getOrNull(1)
                val link = fixUrl(a.attr("href"))
                newEpisode(link) { this.episode = epStr?.toIntOrNull() }
            }.reversed()
        } else {
            d.select("ul.misha_posts_wrap2 > li").mapNotNull {
                val a = it.selectFirst("a") ?: return@mapNotNull null
                val epStr = Regex("Episode\\s?(\\d+)").find(a.text())?.groupValues?.getOrNull(1)
                val link = fixUrl(a.attr("href"))
                newEpisode(link) { this.episode = epStr?.toIntOrNull() }
            }.reversed()
        }

        val recommendations = d.select(".result > li, .related .as-anime-card").mapNotNull {
            val a = it.selectFirst("a") ?: it
            val href = fixUrl(a.attr("href"))
            val recTitle = it.selectFirst("h3, .as-anime-title")?.text()?.trim().ifNullOrBlank { a.attr("title") }
            val recPoster = it.selectFirst(".top > img, .as-card-thumbnail img")?.getImageAttr()
            newAnimeSearchResponse(recTitle ?: "", href, TvType.Anime) {
                posterUrl = recPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, seriesUrl, type) {
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
        val d = app.get(data).document

        val nonce = d.select("script#ajax_video-js-extra")
            .attr("src")
            .substringAfter("base64,", "")
            .let { base64 ->
                Regex("nonce\":\"(\\S+?)\"").find(base64Decode(base64))?.groupValues?.getOrNull(1)
            }

        val servers = d.select(".container1 > ul.player > li.tab-link.tabchs.serverplayer")
        if (servers.isEmpty()) return@coroutineScope true

        servers.map { el ->
            async {
                val dataPost = el.attr("data-post").ifEmpty { d.selectFirst("input[name=series_id]")?.attr("value").orEmpty() }
                val dataNume = el.attr("data-nume").ifEmpty { "1" }
                val serverName = el.attr("data-type").lowercase()

                val html = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "nonce" to (nonce ?: ""),
                        "serverName" to serverName,
                        "nume" to dataNume,
                        "post" to dataPost
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document

                var iframe = html.selectFirst("iframe")?.attr("src")

                if (iframe.isNullOrBlank()) {
                    val ds = html.selectFirst("iframe")?.attr("data-src")
                    if (!ds.isNullOrBlank()) iframe = ds
                }

                if (!iframe.isNullOrBlank() && iframe.contains("/video-frame/")) {
                    val inner = app.get(iframe!!, referer = data).document
                    iframe = inner.select("iframe").attr("data-src").ifEmpty {
                        inner.select("iframe").attr("src")
                    }
                }

                if (!iframe.isNullOrBlank()) {
                    loadExtractor(iframe!!, "$mainUrl/", subtitleCallback, callback)
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

    private data class EpResponse(
        @JsonProperty("posts") val posts: String? = null,
        @JsonProperty("max_page") val max_page: Int? = null,
        @JsonProperty("found_posts") val found_posts: Int? = null,
        @JsonProperty("content") val content: String = ""
    )
}

private fun String?.ifNullOrBlank(block: () -> String): String {
    return if (this.isNullOrBlank()) block() else this
}
