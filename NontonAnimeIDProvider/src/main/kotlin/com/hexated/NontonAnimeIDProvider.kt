package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://nontonanimeid.mom"
    override var name = "NontonAnimeID"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeList = document.select(".listupd .bs").mapNotNull { element ->
            val title = element.selectFirst(".tt h2")?.text()?.trim() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")
            val episode = element.selectFirst(".epx")?.text()?.trim()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.otherInfo = episode
            }
        }
        return newHomePageResponse("Terbaru", homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".listupd .bs").mapNotNull { element ->
            val title = element.selectFirst(".tt h2")?.text()?.trim() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")
            val episode = element.selectFirst(".epx")?.text()?.trim()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.otherInfo = episode
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".infox h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".thumb img")?.attr("src")
        val description = document.selectFirst(".desc p")?.text()?.trim()
        val genre = document.select(".genxed a").map { it.text() }
        val type = if (document.selectFirst(".spe span:contains(Movie)") != null) TvType.AnimeMovie else TvType.Anime

        val episodes = document.select("#epslist > li > a").mapIndexed { index, element ->
            val name = element.text()
            val link = element.attr("href")
            Episode(link, name, episode = index + 1)
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genre
            this.episodes = episodes
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
                try {
                    val decoded = String(Base64.getDecoder().decode(it))
                    Regex("nonce\":\"(\\S+?)\"").find(decoded)?.groupValues?.get(1)
                } catch (_: Exception) {
                    null
                }
            }

        val items = document.select(".container1 > ul > li:not(.boxtab)")

        items.map { element: Element ->
            async {
                try {
                    val dataPost = element.attr("data-post")
                    val dataNume = element.attr("data-nume")
                    val serverName = element.attr("data-type").orEmpty().lowercase()

                    val response = app.post(
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
                    )

                    val iframeSrc = response.document.selectFirst("iframe")?.attr("src")?.let {
                        if (it.contains("/video-frame/")) {
                            app.get(it).document.selectFirst("iframe")?.attr("data-src")
                        } else it
                    }

                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        }.awaitAll()

        true
    }
}
