package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text
        val fileUrl = Regex("""file\s*:\s*["'](.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return

        callback(
            ExtractorLink(
                this.name,
                this.name,
                fileUrl,
                url,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}

class ShortIcu : ExtractorApi() {
    override val name = "ShortIcu"
    override val mainUrl = "https://short.icu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text
        val fileUrl = Regex("""file\s*:\s*["'](.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return

        callback(
            ExtractorLink(
                this.name,
                this.name,
                fileUrl,
                url,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}

suspend fun MainAPI.loadMovieOrEpisodeLinks(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val document = app.get(url).document

    document.select("li.dooplay_player_option[data-url]").forEach { el ->
        val link = el.attr("data-url").trim()
        if (link.isNotEmpty() && link != "about:blank") {
            val extractor = when {
                link.contains("efek.stream") -> EfekStream()
                link.contains("short.icu") -> ShortIcu()
                else -> null
            }
            extractor?.getUrl(link, url, subtitleCallback, callback)
        }
    }
}

suspend fun MainAPI.parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
    val episodes = mutableListOf<Episode>()
    val seasonBlocks = document.select("#seasons .se-c")
    seasonBlocks.forEach { block ->
        val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: 1
        val epList = block.select(".se-a ul.episodios li a")
        epList.forEachIndexed { index, ep ->
            val href = fixUrl(ep.attr("href"))
            val epName = ep.text().ifBlank { "Episode ${index + 1}" }
            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = index + 1
                }
            )
        }
    }
    return episodes
}

private fun fixUrl(url: String): String {
    return if (url.startsWith("http")) url else "https://filmapik.singles$url"
}