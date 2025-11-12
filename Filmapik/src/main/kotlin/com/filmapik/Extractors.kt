package com.filmapik

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Document

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
        val res = app.get(url, referer = referer)
        val html = res.text
        val videoRegex = Regex("""https://[A-Za-z0-9\.-]+/stream/\d+/[A-Za-z0-9]+/__001""")

        videoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value
            val quality = Regex("""/(\d+)/""").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown

            callback(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = videoUrl,
                    referer = url,
                    quality = quality,
                    type = INFER_TYPE
                )
            )
        }
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
        val res = app.get(url, referer = referer)
        val iframe = res.document.selectFirst("iframe")?.attr("src")
        iframe?.let { loadExtractor(it, referer ?: url, subtitleCallback, callback) }
    }
}

class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        val iframe = res.document.selectFirst("iframe")?.attr("src")
        iframe?.let { loadExtractor(it, referer ?: url, subtitleCallback, callback) }
    }
}

class Ico3c : ExtractorApi() {
    override val name = "Ico3c"
    override val mainUrl = "https://ico3c.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        val doc = res.document
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            loadExtractor(iframe, referer ?: url, subtitleCallback, callback)
            return
        }

        val script = doc.select("script").joinToString("\n") { it.data() }
        val redirect = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            .find(script)?.groupValues?.get(1)

        redirect?.let { loadExtractor(it, referer ?: url, subtitleCallback, callback) }
    }
}
