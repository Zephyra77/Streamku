package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element

class Filmapik : MainAPI() {

    override var mainUrl = "https://filmapik.singles"
    override var name = "Filmapik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

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
        val home = document.select("div.items.normal article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href][title]") ?: return null
        val title = a.attr("title").trim()
        val href = fixUrl(a.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img[src]")?.attr("src")).fixImageQuality()
        val ratingText = this.selectFirst("div.rating")?.ownText()?.trim()
        val quality = this.selectFirst("span.quality")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            if (!quality.isNullOrEmpty()) addQuality(quality)
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val img = a.selectFirst("img[src][alt]") ?: return null
        val title = img.attr("alt").trim()
        val posterUrl = fixUrlNull(img.attr("src")).fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text()
            ?.replace("Nonton", "")
            ?.replace("Sub Indo Filmapik", "")
            ?.replace("Subtitle Indonesia Filmapik", "")
            ?.trim() ?: return newMovieLoadResponse("", url, TvType.Movie, url)
        val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrl(it) }
        val tags = document.select("span.sgeneros a").map { it.text() }
        val actors = document.select("span.tagline:contains(Stars:) a").map { it.text() }
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val description = document.selectFirst("div#description, div[itemprop=description]")?.text()?.trim()
        val recommendations = document.select("#single_relacionados article").mapNotNull { it.toRecommendResult() }
        val isSeries = url.contains("/tvshows/") || document.select("div#episodes").isNotEmpty()

        return if (isSeries) {
            val episodes = document.select("div#episodes a").mapIndexed { index, ep ->
                val href = fixUrl(ep.attr("href"))
                val name = ep.text().ifBlank { "Episode ${index + 1}" }
                newEpisode(href) { this.name = name; this.episode = index + 1 }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                addActors(actors)
                this.plot = description
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                addActors(actors)
                this.plot = description
                this.recommendations = recommendations
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
        document.select("div.dooplay_player iframe, div.cframe iframe, iframe.metaframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) loadExtractor(httpsify(src), data, subtitleCallback, callback)
        }
        document.select("div.links_table a.myButton").forEach { linkEl ->
            val downloadUrl = linkEl.attr("href")
            if (downloadUrl.isNotBlank()) loadExtractor(downloadUrl, data, subtitleCallback, callback)
        }
        return true
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun fixUrl(url: String) = when {
        url.startsWith("http") -> url
        else -> "$mainUrl$url"
    }
}
