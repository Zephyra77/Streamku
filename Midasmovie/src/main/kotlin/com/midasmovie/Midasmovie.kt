package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        "/genre/korean-drama/" to "Drama Korea",
        "/genre/animation/" to "Animation",
        "/genre/action/" to "Action",
        "/genre/comedy/" to "Comedy",
        "/genre/drama/" to "Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${mainUrl}${request.data}"
        val doc = app.get(url).document
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(".data h3 a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.text().trim()
        val poster = fixUrlNull(selectFirst(".poster img")?.attr("src"))
        val quality = selectFirst(".mepo .quality")?.text()
        val rating = selectFirst(".rating")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            if (!quality.isNullOrBlank()) addQuality(quality)
            if (!rating.isNullOrBlank()) addSubTitle("Rating $rating")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst(".sheader h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(doc.selectFirst(".poster img")?.attr("src"))
        val description = doc.selectFirst(".wp-content p")?.text()?.trim()
        val genres = doc.select("span.genre a").map { it.text() }
        val actors = doc.select("span.tagline:contains(Stars) a, div.cast a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.toIntOrNull()

        val eps = doc.select(".seasons .se-c .episodios li a")
        return if (eps.isNotEmpty()) {
            val episodes = eps.mapIndexed { i, el ->
                newEpisode(el.attr("href")) {
                    name = el.text().ifBlank { "Episode ${i + 1}" }
                    episode = i + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster ?: ""
                plot = description
                tags = genres
                addActors(actors)
                this.year = year
            }
        } else {
            newMovieLoadResponse(title ?: "", url, TvType.Movie, url) {
                posterUrl = poster ?: ""
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
                referer = data
            ).document

            val iframe = res.selectFirst("iframe")?.attr("src") ?: return@forEach
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return true
    }
}
