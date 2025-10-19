package com.pusatfilm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.apmap

class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        document.select("ul#dropdown-server li a").apmap {
            val frameUrl = base64Decode(it.attr("data-frame"))
            loadExtractor(frameUrl, "$mainUrl/", subtitleCallback, callback)
        }
    }
}