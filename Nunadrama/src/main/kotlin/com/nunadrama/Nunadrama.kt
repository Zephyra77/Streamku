package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    private var directUrl: String? = null

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
        val doc = app.get("$mainUrl/$urlPath").document
        val items = doc.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())
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

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article[itemscope=itemscope], article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        directUrl = mainUrl
        val title = doc.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim().orEmpty()
        val poster = fixUrlNull(doc.selectFirst("figure.pull-left > img")?.getImageAttr())
        val tags = doc.select("div.gmr-moviedata a").map { it.text() }
        val year = doc.select("div.gmr-moviedata strong:contains(Tahun:) > a").text().trim().toIntOrNull()
        val description = doc.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = doc.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = doc.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = doc.select("div.gmr-moviedata span[itemprop=actors]").map { it.select("a").text() }
        val recs = doc.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val isSeries = url.contains("/tv/")
        if (isSeries) {
            val episodeElements = doc.select("div.vid-episodes a, div.gmr-listseries a, div.episodios a")
            val episodes = episodeElements.mapNotNull { eps ->
                val link = fixUrl(eps.attr("href"))
                if (link.isBlank()) return@mapNotNull null
                val name = eps.text().ifBlank { eps.attr("title").ifBlank { "Episode" } }
                val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(link) {
                    this.name = name
                    this.episode = episode
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recs
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
                this.recommendations = recs
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
                val iframe = app.get(fixUrl(ele.attr("href")))
                    .document
                    .selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()
                    ?.let { httpsify(it) }
                    ?: return@amap
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
                ).document
                    .select("iframe")
                    .attr("src")
                    .let { httpsify(it) }

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
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr()?.fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }
}
