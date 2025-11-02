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
        val script = doc.select("script:containsData(sources)").firstOrNull()?.data()
            ?: doc.select("script:containsData(player)").firstOrNull()?.data()
            ?: return@coroutineScope

        Regex("\"file\"\\s*:\\s*\"(https[^\"]+)\",\\s*\"label\"\\s*:\\s*\"(\\d+)p\"")
            .findAll(script)
            .forEach { match ->
                val videoUrl = match.groupValues[1]
                val qualityStr = match.groupValues[2]
                val quality = getQualityFromName("${qualityStr}p")
                val isM3u8 = videoUrl.endsWith(".m3u8")

                callback(
                    newExtractorLink(
                        name,
                        "$name ${qualityStr}p",
                        videoUrl,
                        fixedUrl,
                        quality,
                        isM3u8,
                        headers = mapOf("Referer" to fixedUrl),
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }

        Regex("\"file\"\\s*:\\s*\"(https[^\"]+)\"").findAll(script).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback(
                newExtractorLink(
                    name,
                    name,
                    videoUrl,
                    fixedUrl,
                    Qualities.P720.value,
                    videoUrl.endsWith(".m3u8"),
                    headers = mapOf("Referer" to fixedUrl),
                    type = ExtractorLinkType.VIDEO
                )
            )
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
            val label = src.attr("label").ifBlank {
                src.attr("res").ifBlank {
                    Regex("(\\d{3,4})p").find(videoUrl)?.groupValues?.getOrNull(1) ?: "720"
                }
            }
            if (videoUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        name,
                        "$name ${label}p",
                        videoUrl,
                        workingDomain,
                        getQualityFromName("${label}p"),
                        videoUrl.endsWith(".m3u8"),
                        headers = mapOf("Referer" to workingDomain),
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        val script = doc.select("script:containsData(sources)").firstOrNull()?.data()
        if (script != null) {
            Regex("\"file\"\\s*:\\s*\"(https[^\"]+)\",\\s*\"label\"\\s*:\\s*\"(\\d+)p\"")
                .findAll(script)
                .forEach { match ->
                    val videoUrl = match.groupValues[1]
                    val qualityStr = match.groupValues[2]
                    callback(
                        newExtractorLink(
                            name,
                            "$name ${qualityStr}p",
                            videoUrl,
                            workingDomain,
                            getQualityFromName("${qualityStr}p"),
                            videoUrl.endsWith(".m3u8"),
                            headers = mapOf("Referer" to workingDomain),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
        }
    }
}
