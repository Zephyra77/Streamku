package com.filmapik

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (Any) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { return }

        var fileUrl: String? = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value

        if (fileUrl == null) {
            val jsUrls = Regex("""<script[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.groupValues[1] }.toList()
            for (js in jsUrls) {
                val jsContent = try { app.get(js, referer = url).text } catch (_: Exception) { null }
                if (jsContent != null) {
                    fileUrl = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                        .find(jsContent)?.value
                    if (fileUrl != null) break
                }
            }
        }

        if (fileUrl == null) return

        val finalUrl = if (fileUrl.startsWith("/")) mainUrl + fileUrl else fileUrl

        callback(
            newExtractorLink(name, name, finalUrl) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
