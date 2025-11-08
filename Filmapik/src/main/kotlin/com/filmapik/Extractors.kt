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
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { return }

        var fileUrl = Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)""", RegexOption.IGNORE_CASE)
            .find(html)?.value

        if (fileUrl == null) {
            val rel = Regex("""(/stream/[^\s"'<>]+)""").find(html)?.groupValues?.get(1)
            if (rel != null) {
                val hosts = listOf(
                    "https://v2.efek.stream",
                    "https://v3.efek.stream",
                    "https://v3.goodnews.homes",
                    "https://fa.efek.stream"
                )
                for (host in hosts) {
                    val candidate = host.trimEnd('/') + rel
                    try {
                        val resp = app.head(candidate, referer = url)
                        val ct = resp.headers["content-type"] ?: ""
                        if (resp.status in 200..299 && (ct.contains("mpegurl", true) || ct.contains("video", true))) {
                            fileUrl = candidate
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (fileUrl == null) return

        callback(
            ExtractorLink(
                name,
                name,
                fileUrl,
                url,
                Qualities.Unknown.value
            ).apply {
                referer = referer ?: url
            }
        )
    }
}
