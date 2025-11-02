package com.dramaindo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Mitedrive : ExtractorApi() {
    override val name = "Mitedrive"
    override val mainUrl = "https://mitedrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        val response = app.post(
            "https://api.mitedrive.com/api/view/$id",
            referer = "$mainUrl/",
            data = mapOf(
                "csrf_token" to "ZXlKcGNDSTZJak0yTGpneExqWTFMakUyTWlJc0ltUmxkbWxqWlNJNklrMXZlbWxzYkdFdk5TNHdJQ2hYYVc1a2IzZHpJRTVVSURFd0xqQTdJRmRwYmpZME95QjROalE3SUhKMk9qRXdNUzR3S1NCSFpXTnJieTh5TURFd01ERXdNU0JHYVhKbFptOTRMekV3TVM0d0lpd2lZbkp2ZDNObGNpSTZJazF2ZW1sc2JHRWlMQ0pqYjI5cmFXVWlPaUlpTENKeVpXWmxjbkpsY2lJNklpSjk=",
                "slug" to id
            )
        ).parsedSafe<MiteResponse>()?.data?.url ?: return

        callback.invoke(
            ExtractorLink(
                name,
                name,
                response,
                "$mainUrl/",
                Qualities.P720.value,
                INFER_TYPE
            )
        )
    }

    data class MiteData(
        @JsonProperty("original_url") val url: String?
    )

    data class MiteResponse(
        @JsonProperty("data") val data: MiteData?
    )
}

open class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val video = app.get(url, referer = referer)
            .document
            .selectFirst("video#player source")
            ?.attr("src")
            ?: return

        callback.invoke(
            ExtractorLink(
                name,
                name,
                video,
                "$mainUrl/",
                Qualities.P720.value,
                INFER_TYPE
            )
        )
    }
}
