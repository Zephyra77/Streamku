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
import org.jsoup.nodes.Element
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

    // Fungsi baru untuk menggantikan Requests.get/post langsung
    private suspend fun getDoc(url: String): org.jsoup.nodes.Document =
        Requests.get(url, interceptor = interceptor).document

    private suspend fun postDoc(url: String, data: Map<String, String>): org.jsoup.nodes.Document =
        Requests.post(url, data = data, interceptor = interceptor).document

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = String.format(request.data, page)
        val document = getDoc("$mainUrl/$urlPath")
        val items = document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
        directUrl = getBaseUrl(url)

        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")
            ?.substringBefore("Episode")?.trim().orEmpty()
        val poster = document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality()
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
                    val ajaxDoc = postDoc(
                        "$directUrl/wp-admin/admin-ajax.php",
                        mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId)
                    )
                    epsEls += ajaxDoc.select("a")
                }
            }

            val episodes = epsEls.mapNotNull { eps ->
                val href = eps.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
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
        val document = getDoc(data)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").amap { ele ->
                val iframe = getDoc(fixUrl(ele.attr("href")))
                    .selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()
                    ?.let { httpsify(it) }
                    ?: return@amap

                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").amap { ele ->
                val server = postDoc(
                    "$directUrl/wp-admin/admin-ajax.php",
                    mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("id"),
                        "post_id" to "$id"
                    )
                ).select("iframe").attr("src").let { httpsify(it) }

                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }

        document.select("ul.gmr-download-list li a").forEach { linkEl ->
            val downloadUrl = linkEl.attr("href")
            if (downloadUrl.isNotBlank()) {
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
