package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(kaken =)")?.data()
        val kaken = script?.substringAfter("kaken = \"")?.substringBefore("\"") ?: return

        val json = app.get(
            "$mainUrl/api/?$kaken=&_=${APIHolder.unixTimeMS}",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Response>() ?: return

        json.sources?.forEach { src ->
            val file = src.file ?: return@forEach
            callback.invoke(
                newExtractorLink(name, name, file, INFER_TYPE) {
                    quality = getQuality(json.title)
                }
            )
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Response(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sources") val sources: ArrayList<Source>? = null
    ) {
        data class Source(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("type") val type: String? = null
        )
    }
}

class Nontonanimeid : Hxfile() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.boats"
    override val requiresReferer = true
}

class EmbedKotakAnimeid : Hxfile() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed.kotakanimeid.link"
    override val requiresReferer = true
}

class KotakAnimeidLink : ExtractorApi() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.link"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(sources:)")?.data() ?: return
        val m3u8 = Regex("\"file\"\\s*:\\s*\"(https[^\"]+\\.m3u8)\"").find(script)?.groupValues?.getOrNull(1)
        if (!m3u8.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, name, m3u8, "application/x-mpegURL") {
                    quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class Kotaksb : Hxfile() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.pro"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override val name = "KotakAnimeidCom"
    override val mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true
}

class Vidhidepre : Filesim() {
    override val name = "Vidhidepre"
    override var mainUrl = "https://vidhidepre.com"
}
