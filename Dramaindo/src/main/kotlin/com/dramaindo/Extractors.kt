package com.dramaindo

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class Mitedrive : ExtractorApi() {
    override var name = "Mitedrive"
    override var mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<com.lagradost.cloudstream3.ExtractorLink> {
        val doc = app.get(url).document

        val script = doc.select("script:containsData(player)")?.html()
            ?: return emptyList()

        val videoUrl = Regex("file:\"(https[^\"]+)\"")
            .find(script)?.groupValues?.get(1)
            ?: return emptyList()

        return listOf(
            newExtractorLink(
                source = name,
                name = "$name HD",
                url = videoUrl,
                referer = url,
                quality = Qualities.P720.value,
                type = ExtractorLinkType.VIDEO
            )
        )
    }
}

class Berkasdrive : ExtractorApi() {
    override var name = "Berkasdrive"
    override var mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<com.lagradost.cloudstream3.ExtractorLink> {
        val res = app.get(url)
        val doc = res.document

        val encoded = doc.select("input[name=id]").attr("value")
        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))

        val redirectPage = app.get(decoded, referer = url).text

        val finalUrl = Regex("file\":\"(https[^\"]+)\"")
            .find(redirectPage)?.groupValues?.get(1)
            ?: return emptyList()

        return listOf(
            newExtractorLink(
                source = name,
                name = "$name HD",
                url = finalUrl,
                referer = decoded,
                quality = Qualities.P720.value,
                type = ExtractorLinkType.VIDEO
            )
        )
    }
}
