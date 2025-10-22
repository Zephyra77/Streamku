package com.nunadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.text.RegexOption

class Nunadrama : MainAPI() {
    override var mainUrl = "https://tvnunadrama.store"
    override var name = "Nunadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

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
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlPath = String.format(request.data, page)
        val resp = request("$mainUrl/$urlPath")
        val items = resp.document.select("article[itemscope=itemscope], article.item").mapNotNull {
            val title = it.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrlNull(it.selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = request("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv")
        return resp.document.select("article[itemscope=itemscope], article.item").mapNotNull {
            val title = it.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrlNull(it.selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = request(url)
        val doc = resp.document
        val base = getBaseUrl(resp.url)

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(doc.selectFirst("figure.pull-left img, div.gmr-movie-img img")?.getImageAttr())?.fixImageQuality()
        val description = doc.selectFirst("div[itemprop=description] p, div.entry-content p")?.text()?.trim() ?: "Deskripsi tidak ditemukan"
        val year = doc.selectFirst("div.gmr-moviedata strong:contains(Year:) a")?.text()?.toIntOrNull()
        val rating = doc.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()
        val tags = doc.select("div.gmr-moviedata a").map { it.text() }
        val actors = doc.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text() }

        val episodeSelectors = listOf(
            "div.vid-episodes a",
            "div.gmr-listseries a",
            "div.list-episode a",
            "div.episodios a",
            "ul.episodios li a",
            "ul#episode a",
            "div#episode a",
            "div.dzdesu ul li a"
        )

        val foundEpisodeHrefs = linkedSetOf<String>()
        val episodeElements = mutableListOf<Element>()

        fun collectFromDocument(d: org.jsoup.nodes.Document) {
            for (sel in episodeSelectors) {
                val els = d.select(sel)
                for (e in els) {
                    val href = fixUrl(e.attr("href")).takeIf { it.isNotBlank() } ?: continue
                    if (!foundEpisodeHrefs.contains(href) && !href.contains("coming-soon", true)) {
                        foundEpisodeHrefs.add(href)
                        episodeElements.add(e)
                    }
                }
            }
        }

        collectFromDocument(doc)

        var nextPageUrl: String? = doc.selectFirst("link[rel=next]")?.attr("href")
        if (nextPageUrl.isNullOrBlank()) {
            nextPageUrl = doc.select("a").firstOrNull { it.text().contains("Next", true) || it.hasClass("next") }?.attr("href")
        }

        var pagesFollowed = 0
        while (!nextPageUrl.isNullOrBlank() && pagesFollowed < 8) {
            try {
                val nextDoc = request(nextPageUrl).document
                collectFromDocument(nextDoc)
                nextPageUrl = nextDoc.selectFirst("link[rel=next]")?.attr("href")
                if (nextPageUrl.isNullOrBlank()) {
                    nextPageUrl = nextDoc.select("a").firstOrNull { it.text().contains("Next", true) || it.hasClass("next") }?.attr("href")
                }
                pagesFollowed++
            } catch (_: Exception) {
                break
            }
        }

        if (episodeElements.isEmpty()) {
            val postId = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
                ?: doc.selectFirst("[data-id],[data-post]")?.attr("data-id")
                ?: doc.selectFirst("[data-post]")?.attr("data-post")

            if (!postId.isNullOrBlank()) {
                try {
                    val ajaxDoc = post(
                        "$base/wp-admin/admin-ajax.php",
                        mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId)
                    ).document
                    ajaxDoc.select("a").forEachIndexed { idx, a ->
                        val href = fixUrl(a.attr("href")).takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        if (!foundEpisodeHrefs.contains(href)) {
                            foundEpisodeHrefs.add(href)
                            episodeElements.add(a)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val episodes = episodeElements
            .mapIndexed { idx, e ->
                val rawText = e.text().ifBlank { e.attr("title") }.ifBlank { "Episode ${idx + 1}" }
                val num = Regex("(\\d+)").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val href = fixUrl(e.attr("href"))
                Triple(num, href, rawText)
            }
            .sortedWith(compareByNullsLast { it.first ?: Int.MAX_VALUE })
            .mapIndexed { idx, (num, href, rawText) ->
                newEpisode(href) {
                    name = "Sub Ep ${num ?: idx + 1}"
                    episode = num ?: idx + 1
                }
            }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val base = getBaseUrl(data)
        val doc = app.get(data).document
        val found = linkedSetOf<String>()

        doc.select("iframe, div.gmr-embed-responsive iframe").forEach { f ->
            val src = f.attr("src").ifBlank { f.attr("data-litespeed-src").ifBlank { f.attr("data-src") } }
            val url = httpsify(src ?: "")
            if (url.isNotBlank() && !url.contains("about:blank", true)) found.add(url)
        }

        doc.select("[data-litespeed-src], [data-src], [data-embed]").forEach {
            val maybe = it.attr("data-litespeed-src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-embed") } }
            val url = httpsify(maybe ?: "")
            if (url.isNotBlank()) found.add(url)
        }

        val postId = doc.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
            ?: doc.selectFirst("[data-id],[data-post]")?.attr("data-id")
            ?: doc.selectFirst("[data-post]")?.attr("data-post")

        if (!postId.isNullOrBlank()) {
            try {
                val ajaxDoc = post(
                    "$base/wp-admin/admin-ajax.php",
                    mapOf("action" to "muvipro_player_content", "tab" to "server", "post_id" to postId)
                ).document
                ajaxDoc.select("iframe, a").forEach { el ->
                    val src = if (el.tagName().equals("iframe", true)) el.attr("src") else el.attr("href")
                    val url = httpsify(src)
                    if (url.isNotBlank() && !url.contains("about:blank", true)) found.add(url)
                }
            } catch (_: Exception) {}
        }

        val priorityHosts = listOf("streamwish", "filemoon", "dood", "vidhide", "mixdrop", "sbembed", "listeamed", "upns.pro")
        val sorted = found.sortedBy { link ->
            val idx = priorityHosts.indexOfFirst { link.contains(it, true) }
            if (idx == -1) priorityHosts.size else idx
        }

        for (link in sorted) {
            try {
                loadExtractor(link, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        sorted.isNotEmpty()
    }
}
