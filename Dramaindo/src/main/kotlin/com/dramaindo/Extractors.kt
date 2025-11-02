package com.dramaindo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MiteDrive : ExtractorApi() {
    override val name = "MiteDrive"
    override val mainUrl = "https://mitedrive.com"
    override val requiresReferer = false

    data class Data(@JsonProperty("original_url") val url: String? = null)
    data class Responses(@JsonProperty("data") val data: Data? = null)

    private fun extractId(url: String): String {
        if (url.contains("id=")) {
            val encoded = url.substringAfter("id=")
            return base64Decode(encoded).substringAfterLast("/")
        }
        return url.substringAfterLast("/")
    }

    private fun base64Decode(str: String): String {
        return String(android.util.Base64.decode(str, android.util.Base64.DEFAULT))
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = extractId(url)
        val video = app.post(
            "https://api.mitedrive.com/api/view/$id",
            referer = "$mainUrl/"
        ).parsedSafe<Responses>()?.data?.url ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
                referer = mainUrl,
                quality = getQualityFromName(video)
            )
        )
    }
}

class BerkasDrive : ExtractorApi() {
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
        val video = doc.select("video#player source").attr("src")
        if (video.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
                mainUrl,
                quality = getQualityFromName(video)
            )
        )
    }
}
