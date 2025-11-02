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

    private fun decode(input: String): String {
        return String(android.util.Base64.decode(input, android.util.Base64.DEFAULT))
    }

    private fun extractId(url: String): String {
        return if (url.contains("id=")) {
            val encoded = url.substringAfter("id=")
            decode(encoded).substringAfterLast("/")
        } else {
            url.substringAfterLast("/")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val id = extractId(url)
        val api = "https://api.mitedrive.com/api/view/$id"

        val response = app.post(api, referer = "$mainUrl/")
            .parsedSafe<Responses>()?.data?.url ?: return

        val file = response.lowercase()

        callback.invoke(
            newExtractorLink(
                name,
                name,
                response,
                referer = mainUrl,
                type = if (file.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

        val file = video.lowercase()

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
                referer = mainUrl,
                type = if (file.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )
    }
}
