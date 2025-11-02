package com.dramaindo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.*

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
        val videoUrl = doc.select("video#player source").attr("src")

        if (videoUrl.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "${name} Auto",
                url = videoUrl,
                quality = getQualityFromName(videoUrl),
                headers = mapOf("Referer" to mainUrl)
            )
        )
    }
}

class MiteDrive : ExtractorApi() {
    override val name = "MiteDrive"
    override val mainUrl = "https://mitedrive.com"
    override val requiresReferer = false

    data class Data(@JsonProperty("original_url") val url: String? = null)
    data class Responses(@JsonProperty("data") val data: Data? = null)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        val realUrl =
            app.post("https://api.mitedrive.com/api/view/$id")
                .parsedSafe<Responses>()?.data?.url ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "${name} Auto",
                url = realUrl
            )
        )
    }
}
