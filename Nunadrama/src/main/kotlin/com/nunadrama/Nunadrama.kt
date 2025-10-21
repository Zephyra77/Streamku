package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.text.RegexOption

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
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

    private suspend fun request(url: String) = app.get(url)
    private suspend fun post(url: String, data: Map<String, String>) = app.post(url, data)

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun isPlaceholderText(t: String?): Boolean {
        if (t.isNullOrBlank()) return true
        val low = t.trim().lowercase()
        return low.contains("segera hadir") || low.contains("coming soon") || low.contains("segera") || low.contains("coming")
    }

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
        val items = resp.document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return resp.document.select("article[itemscope=itemscope], article.item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = request(url)
        val baseUrl = getBaseUrl(resp.url)
        val document = resp.document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("Eps")
            ?.trim()
            .orEmpty()

        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] > p")?.text()
            ?: document.selectFirst("div.entry-content p")?.text()
            ?: document.selectFirst("div.gmr-moviedata")?.text()
            ?: "Plot Tidak Ditemukan"
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors]").map { it.select("a").text() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val isSeries = url.contains("/tv/")
        if (isSeries) {
            val epsEls = mutableListOf<Element>()
            epsEls.addAll(document.select("div.vid-episodes a, div.gmr-listseries a, div.episodios a, ul.episodios li a, div.list-episode a"))
            if (epsEls.isEmpty()) {
                val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
                if (!postId.isNullOrEmpty()) {
                    val ajax = post("$baseUrl/wp-admin/admin-ajax.php", mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId))
                    epsEls.addAll(ajax.document.select("a"))
                }
            }
            if (epsEls.isEmpty()) {
                val boxEls = document.select("div.box")
                if (boxEls.isNotEmpty()) {
                    boxEls.forEach { box ->
                        val p = box.selectFirst("p:containsOwn(Episode)")
                        val epText = p?.text()
                        val num = epText?.substringAfter("Episode ")?.filter { it.isDigit() }?.toIntOrNull()
                        val possibleHref = box.selectFirst("a")?.attr("href") ?: "$url?boxeps-${num ?: 0}"
                        if (!possibleHref.isNullOrBlank()) {
                            epsEls.add(Element("a").attr("href", possibleHref).text(epText ?: "Episode ${num ?: 0}"))
                        }
                    }
                }
            }

            val episodes = epsEls.mapNotNull { eps ->
                val rawHref = eps.attr("href").takeIf { it.isNotBlank() && it != "#" } ?: return@mapNotNull null
                if (rawHref.contains("coming-soon", true)) return@mapNotNull null
                val href = fixUrl(rawHref)
                val name = eps.text().ifBlank { eps.attr("title").ifBlank { "Episode" } }
                if (isPlaceholderText(name)) return@mapNotNull null
                val epNum = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("EPISODE\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                }
            }

            val safeEpisodes = if (episodes.isEmpty()) {
                listOf(newEpisode(url) {
                    this.name = "Segera hadir..."
                    this.episode = 0
                })
            } else episodes

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, safeEpisodes) {
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
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val iframe = try {
                    app.get(fixUrl(ele.attr("href")))
                        .document
                        .selectFirst("div.gmr-embed-responsive iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                } catch (t: Throwable) {
                    null
                } ?: return@forEach
                loadExtractor(iframe, getBaseUrl(data), subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").forEach { ele ->
                val server = post(
                    "${getBaseUrl(data)}/wp-admin/admin-ajax.php",
                    mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("id"),
                        "post_id" to "$id"
                    )
                ).document
                    .selectFirst("iframe")
                    ?.attr("src")
                    ?.let { httpsify(it) }
                    ?: return@forEach
                loadExtractor(server, getBaseUrl(data), subtitleCallback, callback)
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
