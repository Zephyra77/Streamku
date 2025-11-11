package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Nontonanimeid : ExtractorApi() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.boats"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val iframe = doc.selectFirst("iframe[data-src*=\"kotakanimeid\"]")?.attr("data-src") ?: return
        KotakAnimeidLink().getUrl(iframe, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class KotakAnimeidLink : ExtractorApi() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://s1.kotakanimeid.link"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(sources:)")?.data() ?: return
        val m3u8 = Regex("\"file\"\\s*:\\s*\"(https[^\"]+\\.m3u8)\"")
            .find(script)?.groupValues?.getOrNull(1) ?: return

        callback.invoke(
            ExtractorLink(
                name,
                name,
                m3u8,
                referer ?: mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
    }
}
