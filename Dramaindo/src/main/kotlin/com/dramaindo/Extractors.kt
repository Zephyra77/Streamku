package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MiteDrive : ExtractorApi() {
    override var name = "MiteDrive"
    override var mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {
        val doc = app.get(url).document

        val script = doc.select("script:containsData(player)")?.html()
            ?: return null

        val videoUrl = Regex("file:\"(https[^\"]+)\"")
            .find(script)?.groupValues?.get(1)
            ?: return null

        val extractorData = mapOf(
            "headers" to mapOf("Referer" to url).toString(),
            "quality" to Qualities.P720.value.toString()
        )

        return listOf(
            newExtractorLink(
                source = name,
                name = "$name HD",
                url = videoUrl,
                extractorData = extractorData.toString()
            )
        )
    }
}

class BerkasDrive : ExtractorApi() {
    override var name = "BerkasDrive"
    override var mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {
        val doc = app.get(url).document

        val encoded = doc.select("input[name=id]").attr("value")
        if (encoded.isNullOrEmpty()) return null

        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))

        val text = app.get(decoded, referer = url).text
        val finalUrl = Regex("file\":\"(https[^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?: return null

        val extractorData = mapOf(
            "headers" to mapOf("Referer" to decoded).toString(),
            "quality" to Qualities.P720.value.toString()
        )

        return listOf(
            newExtractorLink(
                source = name,
                name = "$name HD",
                url = finalUrl,
                extractorData = extractorData.toString()
            )
        )
    }
}
