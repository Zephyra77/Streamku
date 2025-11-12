package com.midasmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*

class MidasMovie : MainAPI() {
    override var mainUrl = "https://midasmovie.live"
    override var name = "MidasMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies/" to "Film Terbaru",
        "/genre/vivamax/" to "Vivamax",
        "/genre/horror/" to "Horor",
        "/genre/korean-drama/" to "Serial Drama",
        "/genre/animation/" to "Animation",
        "/genre/action/" to "Action",
        "/genre/comedy/" to "Comedy",
        "/genre/drama/" to "Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val doc = app.get(url).document
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href][title]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").trim()
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src"))
        val quality = selectFirst(".quality")?.text()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            if (!quality.isNullOrBlank()) addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1[itemprop=name], .sheader h1")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(doc.selectFirst(".poster img")?.attr("src"))
        val description = doc.selectFirst(".wp-content p")?.text()?.trim()
        val genres = doc.select("span.genre a").map { it.text() }
        val actors = doc.select("span.tagline:contains(Stars) a, div.cast a").map { it.text() }
        val year = doc.selectFirst("span.date")?.text()?.toIntOrNull()
        val hasEpisodes = doc.selectFirst("#serie_contenido, #seasons") != null

        return if (hasEpisodes) {
            val episodes = doc.select("#seasons .se-c ul.episodios li").map { el ->
                val epTitle = el.selectFirst(".episodiotitle a")?.text()?.trim().orEmpty()
                val epLink = fixUrl(el.selectFirst(".episodiotitle a")?.attr("href").orEmpty())
                val epPoster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                val epNum = el.selectFirst(".numerando")?.text()?.split("-")?.lastOrNull()?.trim()?.toIntOrNull()
                val epDate = el.selectFirst(".episodiotitle span.date")?.text()?.trim()
                newEpisode(epLink) {
                    this.name = epTitle.ifBlank { "Episode ${epNum ?: 1}" }
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.date = parseDateSafe(epDate)?.time
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                addActors(actors)
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                addActors(actors)
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val links = mutableListOf<String>()

        doc.select("li.dooplay_player_option[data-url]").forEach { el ->
            val url = el.attr("data-url").trim()
            if (url.isNotEmpty()) links.add(url)
        }

        doc.select("div#download a.myButton[href]").forEach { a ->
            val url = a.attr("href").trim()
            if (url.isNotEmpty()) links.add(url)
        }

        if (links.isEmpty()) return false

        for (raw in links) {
            val resolved = resolveIframe(raw)
            loadExtractor(resolved, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveIframe(url: String): String {
        val res = app.get(url, allowRedirects = true)
        val doc = res.document

        doc.selectFirst("iframe[src]")?.attr("src")?.trim()?.let {
            if (it.startsWith("http")) return it
        }

        doc.select("meta[http-equiv=refresh]").forEach { meta ->
            meta.attr("content")?.substringAfter("URL=")?.trim()?.let { refreshUrl ->
                if (refreshUrl.startsWith("http")) return resolveIframe(refreshUrl)
            }
        }

        Regex("""location\.href\s*=\s*["'](.*?)["']""").find(doc.select("script").html())?.groupValues?.get(1)?.let { jsUrl ->
            if (jsUrl.startsWith("http")) return resolveIframe(jsUrl)
        }

        return res.url
    }

    private fun parseDateSafe(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH).parse(dateStr)
        } catch (_: Exception) {
            null
        }
    }
}
