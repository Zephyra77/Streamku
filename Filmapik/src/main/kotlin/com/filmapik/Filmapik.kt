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
import com.filmapik.EfekStream
import com.filmapik.ShortIcu

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
        val url = "$mainUrl/${request.data.format(page)}"
        val document = app.get(url).document
        val items = document.select("div.items.normal article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[title][href]") ?: return null
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
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val img = a.selectFirst("img[src][alt]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = img.attr("alt").trim()
        val poster = fixUrlNull(img.attr("src")).fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name], .sheader h1, .sheader h2")?.text()?.trim()
            ?: document.selectFirst("#info h2")?.text()?.trim()
            ?: ""
        val poster = document.selectFirst(".sheader .poster img")?.attr("src")?.let { fixUrl(it) }
        val genres = document.select("#info .info-more span.sgeneros a").map { it.text() }
        val actors = document.select("#info .info-more span.tagline:contains(Stars) a").map { it.text() }
        val description = document.selectFirst("div[itemprop=description], .wp-content, .entry-content, .desc, .entry")?.text()?.trim()
            ?: document.selectFirst("#info .info-more:nth-of-type(1)")?.text()?.trim()
            ?: "Tidak ada deskripsi."
        val year = document.selectFirst("#info .info-more .country a")?.text()?.toIntOrNull()
        val recommendations = document.select("#single_relacionados article").mapNotNull { it.toRecommendResult() }
        val seasonBlocks = document.select("#seasons .se-c")

        if (seasonBlocks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasonBlocks.forEach { block ->
                val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: 1
                val epList = block.select(".se-a ul.episodios li a")
                epList.forEachIndexed { index, ep ->
                    val href = fixUrl(ep.attr("href"))
                    val epName = ep.text().ifBlank { "Episode ${index + 1}" }
                    episodes.add(
                        newEpisode(href) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = index + 1
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = genres
                addActors(actors)
                this.plot = description
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.tags = genres
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

        document.select("li.dooplay_player_option[data-url]").forEach { el ->
            val link = el.attr("data-url").trim()
            if (link.isNotEmpty() && link != "about:blank") {
                val extractor = when {
                    link.contains("efek.stream") -> EfekStream()
                    link.contains("short.icu") -> ShortIcu()
                    else -> null
                }
                extractor?.getUrl(link, data, subtitleCallback, callback)
            }
        }

        document.select("div#download a.myButton[href]").forEach { el ->
            val href = el.attr("href").trim()
            if (href.isNotEmpty() && href != "about:blank") {
                val extractor = when {
                    href.contains("efek.stream") -> EfekStream()
                    href.contains("short.icu") -> ShortIcu()
                    else -> null
                }
                extractor?.getUrl(href, data, subtitleCallback, callback)
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
