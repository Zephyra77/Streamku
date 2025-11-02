package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class MiteDrive : ExtractorApi() {
    override val name = "MiteDrive"
    override val mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = true

    private val altDomains = listOf(
        "https://mitedrive.my.id",
        "https://mitedrive.lol",
        "https://mitedrive.cloud"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val fixedUrl = altDomains.find { url.contains(it.removePrefix("https://")) } ?: altDomains.first()
        val doc = app.get(url, referer = referer).document

        doc.select("script:containsData(sources), script:containsData(file)").forEach { script ->
            Regex("\"file\"\\s*:\\s*\"([^\"]+)\",\\s*\"label\"\\s*:\\s*\"?(\\d+)p?\"?")
                .findAll(script.data())
                .forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    val qualityStr = match.groupValues[2]
                    val isM3u8 = videoUrl.endsWith(".m3u8")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name ${qualityStr}p",
                            url = videoUrl,
                            referer = fixedUrl,
                            quality = getQualityFromName("${qualityStr}p"),
                            headers = mapOf("Referer" to fixedUrl),
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
        }
    }
}

class BerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://berkasdrive.com"
    override val requiresReferer = true

    private val altDomains = listOf(
        "https://berkasdrive.com",
        "https://dl.berkasdrive.com",
        "https://berkasdrive.net"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val workingDomain = altDomains.find { url.contains(it.removePrefix("https://")) } ?: altDomains.first()
        val doc = app.get(url, referer = referer).document

        doc.select("video source").forEach { src ->
            val videoUrl = src.attr("src").trim()
            if (videoUrl.isNotBlank()) {
                val label = src.attr("label").ifBlank {
                    src.attr("res").ifBlank {
                        Regex("(\\d{3,4})p").find(videoUrl)?.groupValues?.getOrNull(1) ?: "360"
                    }
                }
                val isM3u8 = videoUrl.endsWith(".m3u8")
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name ${label}p",
                        url = videoUrl,
                        referer = workingDomain,
                        quality = getQualityFromName("${label}p"),
                        headers = mapOf("Referer" to workingDomain),
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        doc.select("script:containsData(sources), script:containsData(file)").forEach { script ->
            Regex("\"file\"\\s*:\\s*\"([^\"]+)\",\\s*\"label\"\\s*:\\s*\"?(\\d+)p?\"?")
                .findAll(script.data())
                .forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    val qualityStr = match.groupValues[2]
                    val isM3u8 = videoUrl.endsWith(".m3u8")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name ${qualityStr}p",
                            url = videoUrl,
                            referer = workingDomain,
                            quality = getQualityFromName("${qualityStr}p"),
                            headers = mapOf("Referer" to workingDomain),
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
        }

        doc.select("[data-video], [data-src]").mapNotNull {
            it.attr("data-video").ifBlank { it.attr("data-src") }
        }.forEach { videoUrl ->
            val label = Regex("(\\d{3,4})p").find(videoUrl)?.groupValues?.getOrNull(1) ?: "360"
            val isM3u8 = videoUrl.endsWith(".m3u8")
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name ${label}p",
                    url = videoUrl,
                    referer = workingDomain,
                    quality = getQualityFromName("${label}p"),
                    headers = mapOf("Referer" to workingDomain),
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
    }
}
