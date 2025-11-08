package com.filmapik

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (com.lagradost.cloudstream3.utils.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { return }

        var fileUrl: String? = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value

        if (fileUrl == null) {
            val jsUrls = Regex("""<script[^>]+src=["'](https?://[^"']+)["']""").findAll(html).map { it.groupValues[1] }
            for (js in jsUrls) {
                val jsContent = try { app.get(js, referer = url).text } catch (_: Exception) { null }
                if (jsContent != null) {
                    fileUrl = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(jsContent)?.value
                    if (fileUrl != null) break
                }
            }
        }

        if (fileUrl == null) {
            val rel = Regex("""(/stream/[^\s"'<>]+)""").find(html)?.groupValues?.get(1)
            if (rel != null) {
                val candidates = listOf(
                    "https://v3.goodnews.homes",
                    "https://v3.efek.stream",
                    "https://fa.efek.stream",
                    "https://v2.efek.stream"
                )
                for (base in candidates) {
                    val full = base.trimEnd('/') + rel
                    try {
                        val r = app.head(full, referer = url)
                        if (r.status.value in 200..299) {
                            fileUrl = full
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (fileUrl == null) return

        val finalUrl = if (fileUrl.startsWith("/")) {
            val host = when {
                fileUrl.startsWith("/stream/") -> {
                    val tryHost = listOf("https://v3.goodnews.homes", "https://v2.efek.stream", "https://fa.efek.stream")
                    tryHost.firstNotNullOfOrNull { h ->
                        try {
                            val candidate = h.trimEnd('/') + fileUrl
                            val r = app.head(candidate, referer = url)
                            if (r.status.value in 200..299) candidate else null
                        } catch (_: Exception) { null }
                    } ?: (mainUrl + fileUrl)
                }
                else -> mainUrl + fileUrl
            }
            host
        } else fileUrl

        callback(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
