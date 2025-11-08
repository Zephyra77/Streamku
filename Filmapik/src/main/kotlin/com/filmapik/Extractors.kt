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

        val fileUrl = Regex("""(https?://.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return

        callback(
            ExtractorLink(
                name,
                name,
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

        val fileUrl = Regex("""(https?://.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return

        callback(
            ExtractorLink(
                name,
                name,
                fileUrl,
                url,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
    }
                     }
