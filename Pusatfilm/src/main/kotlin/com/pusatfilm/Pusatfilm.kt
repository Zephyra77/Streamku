package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class Pusatfilm : MainAPI() {
    override var mainUrl = "https://v1.pusatfilm21info.net"
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "film-terbaru/page/%d/" to "Film Terbaru",
        "trending/page/%d/" to "Film Trending",
        "genre/action/page/%d/" to "Film Action",
        "series-terbaru/page/%d/" to "Series Terbaru",
        "drama-korea/page/%d/" to "Drama Korea",
        "west-series/page/%d/" to "West Series",
        "drama-china/page/%d/" to "Drama China",
        "genre/comedy/page/%d/" to "Film Comedy",
        "genre/horror/page/%d/" to "Film Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }

        return HomePageResponse(
            listOf(HomePageList(request.name, home)),
            hasNext = document.select("a.next").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href"))
        val posterUrl = fixUrl(selectFirst("a > img")?.attr("src")).fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim()
        val scoreText = select("div.gmr-meta-rating > span[itemprop=ratingValue]").text().toFloatOrNull()
        val scoreValue = scoreText?.let { Score.from10(it) }

        return if (href != null && quality.isEmpty()) {
            val episode = Regex("Episode\\s?([0-9]+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            AnimeSearchResponse(
                title = title,
                url = href,
                apiName = this@Pusatfilm.name,
                type = TvType.TvSeries
            ).apply {
                this.posterUrl = posterUrl
                this.score = scoreValue
                addSub(episode)
            }
        } else if (href != null) {
            MovieSearchResponse(
                title = title,
                url = href,
                apiName = this@Pusatfilm.name,
                type = TvType.Movie
            ).apply {
                this.posterUrl = posterUrl
                this.score = scoreValue
                addQuality(quality)
            }
        } else null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")
            ?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim() ?: return null
        val poster = fixUrl(document.selectFirst("figure.pull-left > img")?.attr("src"))
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val ratingValue = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.toFloatOrNull()
        val scoreValue = ratingValue?.let { Score.from10(it) }
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }

        val isSeries = url.contains("/tv/")
        return if (isSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").mapNotNull { eps ->
                val epHref = fixUrl(eps.attr("href")) ?: return@mapNotNull null
                val epName = eps.text()
                val episodeNum = epName.filter { it.isDigit() }.toIntOrNull()
                Episode(epHref, episode = episodeNum, name = epName)
            }

            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this@Pusatfilm.name,
                type = TvType.TvSeries,
                episodes = episodes
            ).apply {
                posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = description
                this.score = scoreValue
                this.actors = actors
                addTrailer(trailer)
            }
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this@Pusatfilm.name,
                type = TvType.Movie,
                dataUrl = url
            ).apply {
                posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = description
                this.score = scoreValue
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeEl = document.selectFirst("div.gmr-embed-responsive iframe, div.movieplay iframe, iframe")
        val iframe = listOf("src", "data-src", "data-litespeed-src")
            .firstNotNullOfOrNull { iframeEl?.attr(it)?.takeIf { src -> src.isNotBlank() } }

        if (!iframe.isNullOrBlank()) {
            val refererBase = runCatching { getBaseUrl(iframe) }.getOrDefault(mainUrl) + "/"
            loadExtractor(iframe, refererBase, subtitleCallback, callback)
        }
        return true
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.firstOrNull() ?: return this
        return this.replace(regex, "")
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url else "$mainUrl/${url.trimStart('/')}"
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}