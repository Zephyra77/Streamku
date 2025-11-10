package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class EfekStreamExtractor : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val jsUrl = doc.select("script[src*='jw/']").attr("src")
        if (jsUrl.isNotBlank()) {
            val script = app.get(jsUrl).text
            val m3u8Regex = Regex("https?://[^\"]+\\.m3u8")
            val link = m3u8Regex.find(script)?.value
            if (link != null) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        referer ?: mainUrl,
                        quality = Qualities.Unknown,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        }
    }
}
