package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup

class FilemoonExtractor : MainAPI() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.to"
    override val lang = "id"
    override val hasMainPage = false

    override suspend fun getVideoLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )

        val html = app.get(url, headers).text
        val document = Jsoup.parse(html)

        val iframeUrl = document.selectFirst("iframe")?.attr("src") ?: return emptyList()
        val iframeHtml = app.get(iframeUrl, headers).text

        val m3u8Regex = "(https?://[^\"]+\\.m3u8[^\"]*)".toRegex()
        val m3u8Url = m3u8Regex.find(iframeHtml)?.value ?: return emptyList()

        return listOf(
            ExtractorLink(
                name = "Filemoon",
                url = m3u8Url,
                referer = mainUrl,
                quality = "HD",
                isM3u8 = true
            )
        )
    }
}
