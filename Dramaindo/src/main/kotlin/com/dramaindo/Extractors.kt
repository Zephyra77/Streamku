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
    ) = coroutineScope {
        val doc = app.get(url, referer = referer).document
        val script = doc.select("script:containsData(player)").firstOrNull()?.html() ?: return@coroutineScope

        Regex("file:\"(https[^\"]+)\",label:\"(\\d+)p\"").findAll(script)
            .map { match ->
                val videoUrl = match.groupValues[1]
                val quality = match.groupValues[2].toIntOrNull() ?: Qualities.P720.value
                newExtractorLink(name, "${name} ${quality}p", videoUrl, INFER_TYPE) {
                    this.referer = url
                }
            }.forEach { callback(it) }
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
    ) = coroutineScope {
        val doc = app.get(url, referer = referer).document
        doc.select("video#player source").mapNotNull { src ->
            val videoUrl = src.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = src.attr("label").takeIf { it.isNotBlank() } ?: "720"
            newExtractorLink(name, "${name} ${label}p", videoUrl, INFER_TYPE) {
                this.referer = url
            }
        }.forEach { callback(it) }
    }
}
