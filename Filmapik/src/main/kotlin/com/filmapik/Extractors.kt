package com.filmapik

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.loadExtractor

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val html = app.get(url, referer = referer).text
        val links = mutableListOf<ExtractorLink>()
        val videoRegex = Regex("""https://[A-Za-z0-9\.-]+/stream/\d+/[A-Za-z0-9]+/__001""")

        videoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value
            val quality = Regex("""/(\d+)/""").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            links.add(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = url,
                    quality = quality,
                    type = INFER_TYPE
                )
            )
        }

        return links
    }
}

class ShortIcu : ExtractorApi() {
    override val name = "ShortIcu"
    override val mainUrl = "https://short.icu"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val res = app.get(url, referer = referer)
        val iframe = res.document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            return loadExtractor(iframe, url)
        }
        return emptyList()
    }
}

class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val res = app.get(url, referer = referer)
        val iframe = res.document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            return loadExtractor(iframe, url)
        }
        return emptyList()
    }
}

class Ico3c : ExtractorApi() {
    override val name = "Ico3c"
    override val mainUrl = "https://ico3c.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val res = app.get(url, referer = referer)
        val doc = res.document
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            return loadExtractor(iframe, url)
        }

        val script = doc.select("script").joinToString("\n") { it.data() }
        val redirect = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1)
        if (!redirect.isNullOrEmpty()) {
            return loadExtractor(redirect, url)
        }

        return emptyList()
    }
}
