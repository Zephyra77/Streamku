package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private var directUrl: String? = null

    override val mainPage by lazy {
        mainPageOf(
            "/genre/comedy/" to "Comedy",
            "/genre/drama/" to "Drama",
            "/genre/family/" to "Family",
            "/country/korea/" to "Korea",
            "/country/usa/" to "Hollywood",
            "/country/india/" to "Bollywood"
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = request.data.format(page)
        val document = app.get("$mainUrl/$urlPath").document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")!!.attr("href"))
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode = Regex("Episode\\s?([0-9]+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? = this?.attr("data-litespeed-src")?.takeIf { it.isNotEmpty() }
        ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        return if (tvType == TvType.TvSeries) {
            val episodeElements = document.select("div.vid-episodes a, div.gmr-listseries a, div.gmr-listseries ul li a, div.episodios a")
                .filter { it.attr("href").contains("/tv/") }
            val episodes = episodeElements.map { eps ->
                val href = fixUrl(eps.attr("href"))
                val name = eps.text().ifBlank { "Episode" }
                val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = episode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
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
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").amap { ele ->
                val iframe = app.get(fixUrl(ele.attr("href"))).document
                    .selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()?.let { httpsify(it) } ?: return@amap
                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").amap { ele ->
                val server = app.post(
                    "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("id"),
                        "post_id" to "$id"
                    )
                ).document.select("iframe").attr("src").let { httpsify(it) }
                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }
        document.select("ul.gmr-download-list li a").forEach { linkEl ->
            val downloadUrl = linkEl.attr("href")
            if (downloadUrl.isNotBlank()) loadExtractor(downloadUrl, data, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")!!.attr("href")
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr()?.fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }
}
