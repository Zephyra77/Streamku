package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*

class MidasMovie : MainAPI() {
    override var mainUrl = "https://midasmovie.live"
    override var name = "MidasMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies/" to "Film Terbaru",
        "/genre/vivamax/" to "Vivamax",
        "/genre/horror/" to "Horor",
        "/genre/korean-drama/" to "Serial Drama",
        "/genre/animation/" to "Animation",
        "/genre/action/" to "Action",
        "/genre/comedy/" to "Comedy",
        "/genre/drama/" to "Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val doc = app.get(url).document
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href][title]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").trim()
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src"))
        val quality = selectFirst(".quality")?.text()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            if (!quality.isNullOrBlank()) addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1[itemprop=name], .sheader h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(doc.selectFirst(".poster img")?.attr("src"))
        val description = doc.selectFirst(".wp-content p")?.text()?.trim()
        val genres = doc.select("span.genre a").map { it.text() }
        val actors = doc.select("span.tagline:contains(Stars) a, div.cast a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.toIntOrNull()
        val hasEpisodes = doc.selectFirst("#serie_contenido, #seasons") != null

        if (hasEpisodes) {
            val episodes = mutableListOf<Episode>()
            doc.select("#seasons .se-c ul.episodios li").forEachIndexed { _, el ->
                val epTitle = el.selectFirst(".episodiotitle a")?.text()?.trim().orEmpty()
                val epLink = fixUrl(el.selectFirst(".episodiotitle a")?.attr("href").orEmpty())
                val epPoster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                val epNum = el.selectFirst(".numerando")?.text()?.split("-")?.lastOrNull()?.trim()?.toIntOrNull()
                val epDate = el.selectFirst(".episodiotitle span.date")?.text()?.trim()
                episodes.add(
                    newEpisode(epLink) {
                        name = epTitle.ifBlank { "Episode ${epNum ?: 1}" }
                        episode = epNum
                        posterUrl = epPoster
                        date = parseDateSafe(epDate)?.time
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                tags = genres
                addActors(actors)
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                tags = genres
                addActors(actors)
                this.year = year
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
        val players = doc.select("li.dooplay_player_option[data-nume]")
        if (players.isEmpty()) return false

        players.forEach { li ->
            val postId = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (nume.equals("trailer", true)) return@forEach

            val res = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "id" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
            ).document

            val iframe = res.selectFirst("iframe[src]")?.attr("src")
            if (iframe != null) {
                loadExtractor(iframe, data, subtitleCallback, callback)
            } else {
                val videoSrc = res.selectFirst("source[src]")?.attr("src")
                if (videoSrc != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fixUrl(videoSrc),
                            type = ExtractorLinkType.VIDEO
                        ).apply {
                            setQuality(Qualities.Unknown.value)
                            setIsM3u8(videoSrc.endsWith(".m3u8"))
                        }
                    )
                }
            }
        }
        return true
    }

    private fun parseDateSafe(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH).parse(dateStr)
        } catch (_: Exception) {
            null
        }
    }
}
