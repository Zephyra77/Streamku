package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.qualToInt
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
        val href = fixUrl(selectFirst("a[href]")?.attr("href") ?: return null)
        val posterUrl = selectFirst("img")?.attr("src")
        val qualityText = selectFirst(".mepo .quality")?.text()
        val type = if (href.contains("/tvshows/") || href.contains("/episodes/")) TvType.TvSeries else TvType.Movie

        return newSearchResponse(
            title = title,
            url = href,
            type = type,
            posterUrl = posterUrl,
            quality = qualityText?.qualToInt() ?: 0
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select("#dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("#dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "No title"
        val posterUrl = doc.selectFirst(".poster img")?.attr("src")
        val year = doc.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()
        val plot = doc.selectFirst("div[itemprop=description], .wp-content p")?.text()
        val tags = doc.select(".sgeneros a").map { it.text() }
        val actors = doc.select(".person .data h3").map { ActorData(name = it.text()) }

        val episodes = doc.select("#seasons .se-a ul.episodios li").mapNotNull { ep ->
            val nameEp = ep.selectFirst(".episodiotitle a")?.text()?.trim() ?: return@mapNotNull null
            val linkEp = fixUrl(ep.selectFirst(".episodiotitle a")?.attr("href") ?: return@mapNotNull null)
            val posterEp = ep.selectFirst("img")?.attr("src")
            val epNum = ep.selectFirst(".numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull() ?: 0
            newEpisode(
                name = nameEp,
                url = linkEp,
                episode = epNum,
                posterUrl = posterEp
            )
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(
                title = title,
                url = url,
                type = TvType.TvSeries,
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                episodes = episodes,
                actors = actors
            )
        } else {
            newMovieLoadResponse(
                title = title,
                url = url,
                type = TvType.Movie,
                posterUrl = posterUrl,
                year = year,
                plot = plot,
                tags = tags,
                actors = actors
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val sources = doc.select("li.dooplay_player_option")

        for (src in sources) {
            val postId = src.attr("data-post")
            val nume = src.attr("data-nume")
            val type = src.attr("data-type")

            val ajaxResponse = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val iframeUrl = ajaxResponse.selectFirst("iframe")?.attr("src") ?: continue
            loadExtractor(fixUrl(iframeUrl)) { link: ExtractorLink ->
                callback(link)
            }
        }

        return true
    }
}
