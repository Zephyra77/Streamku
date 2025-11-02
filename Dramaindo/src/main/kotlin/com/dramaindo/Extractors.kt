package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope

open class MiteDrive : ExtractorApi() {
    override val name = "MiteDrive"
    override val mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val script = doc.select("script:containsData(player)")?.html() ?: return

        Regex("file:\"(https[^\"]+)\",label:\"(\\d+)p\"").findAll(script).forEach { match ->
            val videoUrl = match.groupValues[1]
            val quality = match.groupValues[2].toIntOrNull() ?: Qualities.P720.value
            callback(newExtractorLink(name, "${name} ${quality}p", videoUrl, INFER_TYPE) {
                this.referer = url
            })
        }
    }
}

open class BerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        doc.select("video#player source").mapNotNull { src ->
            val videoUrl = src.attr("src")
            val label = src.attr("label").takeIf { it.isNotBlank() } ?: "720"
            callback(newExtractorLink(name, "${name} ${label}p", videoUrl, INFER_TYPE) {
                this.referer = url
            })
        }
    }
}