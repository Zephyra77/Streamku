package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MidasMovie : MainAPI() {
    override var name = "MidasMovie"
    override var mainUrl = "https://midasmovie.live"
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
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".episodiotitle a, h3 a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a[href]")?.attr("href") ?: return null)
        val poster = selectFirst("img")?.attr("src")
        val quality = selectFirst(".mepo .quality")?.text()
        val rating = selectFirst(".rating")?.text()?.replace(",", ".")?.toFloatOrNull()

        return MovieSearchResponse(
            name = title,
            url = href,
            posterUrl = poster,
            quality = getQualityFromString(quality),
            rating = rating,
            type = if (href.contains("/tvshows/") || href.contains("/episodes/")) TvType.TvSeries else TvType.Movie
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select("div.episodios li, #dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.episodios li, #dt-movies article.item.movies").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "No title"
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val year = doc.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()
        val plot = doc.selectFirst("div[itemprop=description], .wp-content p")?.text()
        val rating = doc.selectFirst(".dt_rating_vgs")?.text()?.toFloatOrNull()
        val tags = doc.select(".sgeneros a").map { it.text() }
        val actors = doc.select(".person .data h3").map { it.text() }

        val episodes = mutableListOf<Episode>()
        doc.select("#seasons .se-a ul.episodios li").forEach { ep ->
            val nameEp = ep.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
            val linkEp = fixUrl(ep.selectFirst(".episodiotitle a")?.attr("href") ?: return@forEach)
            val posterEp = ep.selectFirst("img")?.attr("src")
            val epNum = ep.selectFirst(".numerando")?.text()?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()
            episodes.add(Episode(name = nameEp, url = linkEp, episode = epNum, posterUrl = posterEp))
        }

        return if (episodes.isNotEmpty()) {
            TvSeriesLoadResponse(
                name = title,
                url = url,
                posterUrl = poster,
                year = year,
                episodes = episodes,
                plot = plot,
                tags = tags,
                rating = rating,
                type = TvType.TvSeries
            ).addActors(actors)
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                posterUrl = poster,
                year = year,
                plot = plot,
                tags = tags,
                rating = rating,
                type = TvType.Movie
            ).addActors(actors)
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
            val post = src.attr("data-post")
            val nume = src.attr("data-nume")
            val type = src.attr("data-type")

            val ajaxResponse = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val iframeUrl = ajaxResponse.selectFirst("iframe")?.attr("src")
                ?: ajaxResponse.selectFirst("source")?.attr("src")
                ?: continue

            loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
        }

        return true
    }
}