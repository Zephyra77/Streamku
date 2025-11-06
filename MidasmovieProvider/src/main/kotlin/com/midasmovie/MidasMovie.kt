package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MidasMovie : MainAPI() {
    override var mainUrl = "https://midasmovie.live"
    override var name = "MidasMovie"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/adventure/" to "Adventure",
        "$mainUrl/genre/animation/" to "Anime",
        "$mainUrl/genre/drama/" to "Drama",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/sci-fi-fantasy/" to "Sci-Fi & Fantasy"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3 a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a[href]")!!.attr("href"))
        val poster = selectFirst("img")?.attr("src")
        val quality = getQualityFromString(selectFirst(".mepo .quality")?.text())

        val type = if (href.contains("/tvshows/") || href.contains("/episodes/"))
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.quality = quality
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val list = doc.select("#dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("#dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "No title"
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val year = doc.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()
        val plot = doc.selectFirst("div[itemprop=description], .wp-content p")?.text()
        val tags = doc.select(".sgeneros a").map { it.text() }

        val episodes = doc.select("#seasons .se-a ul.episodios li").mapNotNull {
            val name = it.selectFirst(".episodiotitle a")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrl(it.selectFirst(".episodiotitle a")!!.attr("href"))
            val posterEp = it.selectFirst("img")?.attr("src")
            val epNum = it.selectFirst(".numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull()

            newEpisode(link) {
                this.name = name
                this.posterUrl = posterEp
                this.episode = epNum
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
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
        val items = doc.select("li.dooplay_player_option")

        for (it in items) {
            val post = it.attr("data-post")
            val nume = it.attr("data-nume")
            val type = it.attr("data-type")

            val ajax = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val iframe = ajax.selectFirst("iframe")?.attr("src") ?: continue
            loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
        }

        return true
    }
}
