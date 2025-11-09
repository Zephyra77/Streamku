package com.filmapik

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.app

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    private val hosts = listOf(
        "https://v3.goodnews.homes",
        "https://v3.efek.stream",
        "https://fa.efek.stream",
        "https://v2.efek.stream"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { return }

        var fileUrl = Regex("""https?://[^'"<>]+/stream/\d+/[^\s"'<>]+/__\d+""").find(html)?.value

        if (fileUrl == null) {
            val jsUrls = Regex("""<script[^>]+src=["'](https?://[^"']+)["']""").findAll(html).map { it.groupValues[1] }
            for (js in jsUrls) {
                val jsContent = try { app.get(js, referer = url).text } catch (_: Exception) { null }
                if (jsContent != null) {
                    fileUrl = Regex("""https?://[^'"<>]+/stream/\d+/[^\s"'<>]+/__\d+""").find(jsContent)?.value
                    if (fileUrl != null) break
                }
            }
        }

        if (fileUrl == null) {
            val rel = Regex("""(/stream/[^\s"'<>]+/__\d+)""").find(html)?.groupValues?.get(1)
            if (rel != null) {
                fileUrl = hosts.firstNotNullOfOrNull { host ->
                    try {
                        val full = host.trimEnd('/') + rel
                        val r = app.head(full, referer = url)
                        if (r.statusCode in 200..299) full else null
                    } catch (_: Exception) { null }
                }
            }
        }

        if (fileUrl == null) return

        callback(
            newExtractorLink(name, name, fileUrl) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
