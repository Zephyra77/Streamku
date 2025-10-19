package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OppadramaProvider : MainAPI() {
    override var mainUrl = "http://45.11.57.243"
    override var name = "Oppadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t.trim().lowercase()) {
                "completed", "complete", "selesai" -> ShowStatus.Completed
                "ongoing", "berlangsung", "sedang tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun getType(t: String?): TvType {
            return when {
                t?.contains("movie", true) == true -> TvType.Movie
                t?.contains("anime", true) == true -> TvType.Anime
                else -> TvType.AsianDrama
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Drama Terbaru",
        "&order=latest" to "Baru Ditambahkan",
        "&status=&type=&order=popular" to "Drama Populer",
        "&status=&type=movie&order=update" to "Film Asia",
        "&status=&type=drama&order=update" to "Drama Asia",
        "&status=&type=anime&order=update" to "Anime Jepang",
        "&country=korea&order=update" to "Drama Korea",
        "&country=japan&order=update" to "Drama Jepang",
        "&country=china&order=update" to "Drama China",
        "&country=thailand&order=update" to "Drama Thailand",
        "&country=hongkong&order=update" to "Drama Hongkong",
        "&country=taiwan&order=update" to "Drama Taiwan"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/series/?page=$page${request.data}").document
        val items = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            val match = Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.getOrNull(1)
            "$mainUrl/series/$match"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")?.attr("href") ?: return null)
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.select("img:last-child").attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.select("div.thumb img:last-child").attr("src"))
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d{4}").find(
            document.selectFirst(".info-content > .spe > span > time")?.text()?.trim() ?: ""
        )?.value?.toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                ?.text()?.trim()?.replace("Status: ", "") ?: ""
        )
        val description = document.select(".entry-content > p").joinToString("\n") { it.text() }.trim()
        val episodes = document.select(".eplister > ul > li").mapNotNull {
            val name = it.selectFirst(".epl-title")?.text()
            val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            newEpisode(link) { this.name = name }
        }.reversed()
        val recommendations = document.select(".listupd > article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newTvSeriesLoadResponse(title, url, getType(type), episodes = episodes) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
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
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            val decoded = base64Decode(it.attr("value"))
            val src = Jsoup.parse(decoded).select("iframe").attr("src")
            fixUrl(src)
        }
        sources.apmap {
            loadExtractor(it, mainUrl, subtitleCallback, callback)
        }
        return sources.isNotEmpty()
    }
}
