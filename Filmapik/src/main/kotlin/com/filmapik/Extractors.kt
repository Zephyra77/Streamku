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
        val jsUrls = Regex("""<script[^>]+src=["'](.*?\.js[^"']*)["']""").findAll(html)
            .map { it.groupValues[1] }.toList()

        var fileUrl: String? = null
        for (js in jsUrls) {
            val jsContent = try { app.get(js, referer = url).text } catch(e: Exception) { continue }
            fileUrl = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(jsContent)?.value
            if (fileUrl != null) break
        }
        if (fileUrl == null) fileUrl = Regex("""(https?://.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
        if (fileUrl == null) return

        callback(
            ExtractorLink(name, name, fileUrl, url, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
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
        val jsUrls = Regex("""<script[^>]+src=["'](.*?\.js[^"']*)["']""").findAll(html)
            .map { it.groupValues[1] }.toList()

        var fileUrl: String? = null
        for (js in jsUrls) {
            val jsContent = try { app.get(js, referer = url).text } catch(e: Exception) { continue }
            fileUrl = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(jsContent)?.value
            if (fileUrl != null) break
        }
        if (fileUrl == null) fileUrl = Regex("""(https?://.*?\.m3u8.*?)["']""").find(html)?.groupValues?.get(1)
        if (fileUrl == null) return

        callback(
            ExtractorLink(name, name, fileUrl, url, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
        )
    }
}
