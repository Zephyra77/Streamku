package com.dramaindo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Mitedrive : ExtractorApi() {
    override val name = "Mitedrive"
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
        val video = app.post(
            "https://api.mitedrive.com/api/view/$id",
            referer = "$mainUrl/",
            data = mapOf(
                "csrf_token" to "ZXlKcGNDSTZJak0yTGpneExqWTFMakUyTWlJc0ltUmxkbWxqWlNJNklrMXZlbWxzYkdFdk5TNHdJQ2hYYVc1a2IzZHpJRTVVSURFd0xqQTdJRmRwYmpZME95QjROalE3SUhKMk9qRXdNUzR3S1NCSFpXTnJieTh5TURFd01ERXdNU0JHYVhKbFptOTRMekV3TVM0d0lpd2lZbkp2ZDNObGNpSTZJazF2ZW1sc2JHRWlMQ0pqYjI5cmFXVWlPaUlpTENKeVpXWmxjbkpsY2lJNklpSjk=",
                "slug" to id
            )
        ).parsedSafe<Responses>()?.data?.url ?: return

        callback(
            newExtractorLink(
                name,
                name,
                video,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
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
        val doc = app.get(url, referer = referer).document
        val buttons = doc.select("a.btn.btn-primary.btn-block")

        if (buttons.isNotEmpty()) {
            buttons.forEach { btn ->
                val href = btn.attr("href")
                val quality = Regex("(\\d{3,4})p").find(btn.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value

                callback(
                    ExtractorLink(
                        name,
                        "$name ${btn.text()}",
                        href,
                        url,
                        quality,
                        INFER_TYPE
                    )
                )
            }
            return
        }

        val src = doc.select("video#player source").attr("src")
        if (src.isNotBlank()) {
            callback(
                ExtractorLink(
                    name,
                    "$name Default",
                    src,
                    url,
                    Qualities.Unknown.value,
                    INFER_TYPE
                )
            )
        }
    }
}
