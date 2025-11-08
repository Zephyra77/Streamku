package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Filmapik : MainAPI() {

    override var mainUrl = "https://filmapik.singles"
    override var name = "Filmapik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "tvshows/page/%d/" to "Serial Terbaru",
        "latest/page/%d/" to "Film Terbaru",
        "category/action/page/%d/" to "Action",
        "category/romance/page/%d/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("div.items.normal article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href][title]") ?: return null
        val title = a.attr("title").trim()
        val href = fixUrl(a.attr("href"))
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src")).fixImageQuality()
        val rating = selectFirst("div.rating")?.ownText()?.trim()?.toDoubleOrNull()
        val quality = selectFirst("span.quality")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            if (!quality.isNullOrBlank()) addQuality(quality)
            this.score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val img = a.selectFirst("img[src][alt]") ?: return null

        val href = fixUrl(a.attr("href"))
        val title = img.attr("alt").trim()
        val poster = fixUrlNull(img.attr("src")).fixImageQuality()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("#info h2")?.text()?.trim()
            ?: document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: ""

        val poster = document.selectFirst(".sheader .poster img")
            ?.attr("src")?.let { fixUrl(it) }

        val tags = document.select("span.sgeneros a").map { it.text() }
        val actors = document.select("#info .tagline:contains(Stars:) a").map { it.text() }
        val year = document.selectFirst("#info .country a")?.text()?.toIntOrNull()
        val description = document.selectFirst("#info .info-more")?.text()?.trim()

        val recommendations = document.select("#single_relacionados article").mapNotNull {
            it.toRecommendResult()
        }

        val episodeList = document.select("#episodes ul.episodios li a")

        if (episodeList.isNotEmpty()) {
            val episodes = episodeList.mapIndexed { index, ep ->
                val href = fixUrl(ep.attr("href"))
                val name = ep.text().ifBlank { "Episode ${index + 1}" }
                newEpisode(href) {
                    this.name = name
                    this.episode = index + 1
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                addActors(actors)
                this.plot = description
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            addActors(actors)
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframeLinks = document.select("div.cframe iframe, iframe.metaframe")
        iframeLinks.forEach { frame ->
            val src = frame.attr("src").trim()
            if (src.isNotEmpty()) {
                loadExtractor(
                    httpsify(src),
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        val downloadLinks = document.select("div.links_table a.myButton")
        downloadLinks.forEach { dl ->
            val href = dl.attr("href").trim()
            if (href.isNotEmpty()) {
                loadExtractor(
                    href,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.firstOrNull()
        return if (match != null) this.replace(match, "") else this
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
