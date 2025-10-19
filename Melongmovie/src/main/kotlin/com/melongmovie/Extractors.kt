package com.melongmovie

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.httpsify

class Earnvids : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val regex = Regex("""file\s*:\s*"([^"]+)"""")

        val doc = app.get(url, referer = referer).document
        val videoUrl = regex.find(doc.html())?.groupValues?.getOrNull(1)

        if (videoUrl != null) {
            links.add(newExtractorLink(name, httpsify(videoUrl), url))
        } else {
            val iframe = doc.selectFirst("iframe")?.attr("src")
            if (iframe != null && !iframe.contains("youtube", true)) {
                val innerDoc = app.get(iframe, referer = url).document
                val innerUrl = regex.find(innerDoc.html())?.groupValues?.getOrNull(1)
                if (innerUrl != null) links.add(newExtractorLink(name, httpsify(innerUrl), url))
            }
        }

        return links
    }
}