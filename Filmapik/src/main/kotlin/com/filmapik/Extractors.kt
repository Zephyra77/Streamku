package com.filmapik

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        val doc = res.document
        val links = mutableListOf<ExtractorLink>()

        doc.select("script").forEach { script: Element ->
            val data = script.data()
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = try {
                    getAndUnpack(data)
                } catch (e: Exception) {
                    null
                } ?: return@forEach

                val fileRegex = Regex("\"file\"\\s*:\\s*['\"](.*?)['\"]")
                val labelRegex = Regex("\"label\"\\s*:\\s*['\"](.*?)['\"]")
                val files = fileRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
                val labels = labelRegex.findAll(unpacked).map { it.groupValues[1] }.toList()

                files.forEachIndexed { idx, filePath ->
                    val label = labels.getOrNull(idx) ?: ""
                    val quality = when (label) {
                        "1080p" -> Qualities.P1080
                        "720p" -> Qualities.P720
                        "360p" -> Qualities.P360
                        else -> Qualities.Unknown
                    }
                    val finalUrl = if (filePath.startsWith("http")) filePath else "${mainUrl.trimEnd('/')}$filePath"

                    links.add(
                        newExtractorLink(
                            name,
                            "$name $label",
                            url = finalUrl
                        ) { this.quality = quality }
                    )
                }

                val downloadRegex = Regex("window\\.open\\([\"']([^\"']+)[\"']")
                val downloadMatch = downloadRegex.find(unpacked)
                downloadMatch?.groupValues?.getOrNull(1)?.let { dl ->
                    val dlFinal = if (dl.startsWith("http")) dl else "${mainUrl.trimEnd('/')}$dl"
                    links.add(
                        newExtractorLink(
                            name,
                            "$name Download",
                            url = dlFinal
                        ) { this.quality = Qualities.Unknown }
                    )
                }
            }
        }

        val html = res.text
        Regex("/stream/\\d+/[A-Za-z0-9]+/__001").findAll(html).forEach { m ->
            val filePath = m.value
            val finalUrl = "${mainUrl.trimEnd('/')}$filePath"
            val qFromPath = Regex("/stream/(\\d+)/").find(filePath)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val quality = when (qFromPath) {
                1080 -> Qualities.P1080
                720 -> Qualities.P720
                360 -> Qualities.P360
                else -> Qualities.Unknown
            }
            links.add(
                newExtractorLink(
                    name,
                    name,
                    url = finalUrl
                ) { this.quality = quality }
            )
        }

        val bestLink = links.find { it.quality == Qualities.P1080 }
            ?: links.find { it.quality == Qualities.P720 }
            ?: links.find { it.quality == Qualities.P360 }
            ?: links.firstOrNull()

        bestLink?.let { callback(it) }
    }
}
