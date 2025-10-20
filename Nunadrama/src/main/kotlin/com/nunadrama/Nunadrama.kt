package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI
import kotlin.text.RegexOption

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    private var directUrl: String? = null
    private val interceptor by lazy { CloudflareKiller() }

    override val mainPage by lazy {
        mainPageOf(
            "page/%d/?s&search=advanced&post_type=movie" to "All Movies",
            "genre/drama/page/%d/" to "Korean Series",
            "genre/j-drama/page/%d/" to "Japan Series",
            "genre/c-drama/page/%d/" to "China Series",
            "genre/thai-drama/page/%d/" to "Thailand Series",
            "genre/koleksi-series/page/%d/" to "Other Series",
            "genre/variety-show/page/%d/" to "Variety Show"
        )
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse =
        Requests.get(url, headers = ref, interceptor = interceptor)

    private suspend fun post(url: String, data: Map<String, String>): NiceResponse =
        Requests.post(url, data = data, interceptor = interceptor)

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        val a = this ?: return null
        val d = a.attr("data-litespeed-src")
        return if (d.isNotEmpty()) d else a.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("(-\\d*x\\d*)").find(this) ?: return this
        return replace(match.groupValues[0], "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality(quality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = String.format(request.data, page)
        val resp = request("$mainUrl/$urlPath")
        val items = resp.document.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return resp.document.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = request(url)
        directUrl = getBaseUrl(resp.url)
        val document = resp.document
        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors]").map { it.select("a").text() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val isSeries = url.contains("/tv/")
        if (isSeries) {
            val epsEls = mutableListOf<Element>()
            epsEls += document.select("div.vid-episodes a, div.gmr-listseries a, div.episodios a")

            if (epsEls.isEmpty()) {
                val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
                if (!postId.isNullOrEmpty()) {
                    val ajax = post("$directUrl/wp-admin/admin-ajax.php", mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId))
                    epsEls += ajax.document.select("a")
                }
            }

            val episodes = epsEls.mapNotNull { eps ->
                val href = eps.attr("href").let { if (it.isNotBlank()) fixUrl(it) else null } ?: return@mapNotNull null
                val name = eps.text().ifBlank { eps.attr("title").ifBlank { "Episode" } }
                val epNum = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
            val tabs = document.select("ul.muvipro-player-tabs li a")
            for (ele in tabs) {
                val href = ele.attr("href")
                if (href.isBlank()) continue
                val page = app.get(fixUrl(href)).document
                val iframe = page.selectFirst("div.gmr-embed-responsive iframe") ?: page.selectFirst("iframe")
                val src = iframe?.getIframeAttr()?.let { httpsify(it) } ?: continue
                loadExtractor(src, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            val tabs = document.select("div.tab-content-ajax")
            for (ele in tabs) {
                val resp = app.post("$directUrl/wp-admin/admin-ajax.php", data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to id))
                val iframe = resp.document.selectFirst("iframe")
                val src = iframe?.attr("src")?.let { httpsify(it) } ?: continue
                loadExtractor(src, "$directUrl/", subtitleCallback, callback)
            }
        }

        val downloads = document.select("ul.gmr-download-list li a")
        for (linkEl in downloads) {
            val downloadUrl = linkEl.attr("href")
            if (downloadUrl.isNotBlank()) loadExtractor(downloadUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterEl = selectFirst("a > img") ?: selectFirst("div.content-thumbnail img")
        val poster = posterEl?.let { getImageAttrFromElement(it) }?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }
    }

    private fun getImageAttrFromElement(el: Element): String? {
        return when {
            el.hasAttr("data-src") -> el.attr("abs:data-src")
            el.hasAttr("data-lazy-src") -> el.attr("abs:data-lazy-src")
            el.hasAttr("srcset") -> el.attr("abs:srcset").substringBefore(" ")
            else -> el.attr("abs:src")
        }
    }
}
