package com.kitanonton

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI

open class Kitanonton : MainAPI() {
    override var mainUrl = "https://kitanonton2.blog/"
    private var directUrl: String? = null
    override var name = "KitaNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/genre/populer/page/" to "Populer Movies",
        "$mainUrl/movies/page/" to "New Movies",
        "$mainUrl/genre/westseries/page/" to "West TV Series",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/animation/page/" to "Anime",
        "$mainUrl/genre/series-indonesia/page/" to "Drama Indonesia",
        "$mainUrl/genre/drama-jepang/page/" to "Drama Jepang",
        "$mainUrl/genre/drama-china/page/" to "Drama China",
        "$mainUrl/genre/thailand-series/page/" to "Drama Thailand"
    )

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.mli-info > h2")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val type = if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = fixUrlNull(this.select("img").attr("src"))
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl = fixUrlNull(
                this.select("img").attr("src")
                    .ifEmpty { this.select("img").attr("data-original") }
            )
            val episode = this.select("div.mli-eps > span").text().replace(Regex("[^0-9]"), "").toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val req = app.get(url)
        directUrl = getBaseUrl(req.url)
        val document = req.document
        val title = document.selectFirst("h3[itemprop=name]")!!.ownText().trim()
        val poster = document.select(".mvic-desc > div.thumb.mvic-thumb").attr("style")
            .substringAfter("url(").substringBeforeLast(")")
        val tags = document.select("span[itemprop=genre]").map { it.text() }
        val year = Regex("([0-9]{4}?)-").find(document.selectFirst(".mvici-right > p:nth-child(3)")!!.ownText().trim())
            ?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val trailer = fixUrlNull(document.selectFirst("div.modal-body-trailer iframe")?.attr("src"))

        // ganti rating ke Score baru
        val ratingFloat = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toFloatOrNull() ?: 0f
        val score = newScore(ratingFloat)

        val duration = document.selectFirst(".mvici-right > p:nth-child(1)")!!.ownText().replace(Regex("[^0-9]"), "").toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map { it.select("span").text() }
        val baseLink = fixUrl(document.select("div#mv-info > a").attr("href").toString())

        return if (tvType == TvType.TvSeries) {
            val episodes = app.get(baseLink).document.select("div#list-eps > a")
                .map { Pair(it.text(), it.attr("data-iframe")) }
                .groupBy { it.first }
                .map { eps ->
                    newEpisode(data = eps.value.map { fixUrl(base64Decode(it.second)) }.toString()) {
                        this.name = eps.key
                        this.episode = eps.key.filter { it.isDigit() }.toIntOrNull()
                    }
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val links = app.get(baseLink).document.select("div#server-list div.server-wrapper div[id*=episode]")
                .map { fixUrl(base64Decode(it.attr("data-iframe"))) }.toString()
            newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration
                addActors(actors)
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
        data.removeSurrounding("[", "]").split(",").map { it.trim() }.apmap { link ->
            safeApiCall {
                loadExtractor(link, "$directUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
