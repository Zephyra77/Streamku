package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

open class Klikxxi : MainAPI() {
    override var mainUrl = "https://www.klikxxi.com"
    private var directUrl: String? = null
    override var name = "Klikxxi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/?s&search=advanced&post_type=movie" to "Movies Terbaru",
        "$mainUrl/country/india/page/%d/" to "Movies India",
        "$mainUrl/country/korea/page/%d/" to "Movies Korea",
        "$mainUrl/country/china/page/%d/" to "Movies China"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val document = app.get(fixUrl(url)).document
        val list = document.select("article.item, div.gmr-item, div.item-movie, div.item-series")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("h2.entry-title > a, a[title], a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val rawTitle = link.attr("title").ifBlank { link.text() }.trim()
        val title = rawTitle.removePrefix("Permalink to: ").trim()
        if (title.isBlank()) return null
        val posterUrl = selectFirst("img")?.getImageAttr()?.fixImageQuality()
        val quality = selectFirst("span.gmr-quality-item")?.text()?.trim()
            ?: select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        val isSeries = selectFirst(".gmr-posttype-item")?.text()?.contains("TV", true) == true || href.contains("/series/") || href.contains("/tv/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val title = selectFirst("a > span.idmuvi-rp-title")?.text()?.trim()
            ?: link.attr("title").ifBlank { link.text() }.trim()
        val href = fixUrl(link.attr("href"))
        val posterUrl = selectFirst("a > img")?.getImageAttr()?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item, div.gmr-item, div.item-movie, div.item-series").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title, div.mvic-desc h3")?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()
        val poster = document.selectFirst("figure img, div.thumb img")?.getImageAttr()?.fixImageQuality()
        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata strong:contains(Genre:) > a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()?.toInt()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text() }.takeIf { it.isNotEmpty() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()
        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)").find(seasonTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val eps = block.select("div.gmr-season-episodes a")
                .filter { a -> !a.text().lowercase().contains("view all") && !a.text().lowercase().contains("batch") }
                .mapIndexedNotNull { index, epLink ->
                    val href = epLink.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapIndexedNotNull null
                    val name = epLink.text().trim()
                    val episodeNum = Regex("E(p|ps)?(\\d+)").find(name)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (index + 1)
                    newEpisode(href) {
                        this.name = name
                        this.season = seasonNumber
                        this.episode = episodeNum
                    }
                }
            allEpisodes.addAll(eps)
        }

        val episodes = allEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val tvType = if (episodes.isNotEmpty() || url.contains("/series/") || url.contains("/tv/")) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = rating   
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = rating   
                addActors(actors)
                addTrailer(trailer)
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
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (postId.isNullOrBlank()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val iframe = app.get(fixUrl(ele.attr("href")))
                    .document.selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").forEach { ele ->
                val server = app.post(
                    "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("id"),
                        "post_id" to "$postId"
                    )
                ).document.select("iframe").attr("src").let { httpsify(it) }
                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-srcset") -> attr("abs:data-srcset").substringBefore(" ")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true } ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
