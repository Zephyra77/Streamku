package com.filmapik

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
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

        var links = mutableListOf<ExtractorLink>()

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
                    val quality = parseQualityFromLabel(label)
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
                        ) { this.quality = Qualities.Unknown.value }
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
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                360 -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            links.add(
                newExtractorLink(
                    name,
                    name,
                    url = finalUrl
                ) { this.quality = quality }
            )
        }

        val bestLink = links.find { it.quality == Qualities.P1080.value }
            ?: links.find { it.quality == Qualities.P720.value }
            ?: links.find { it.quality == Qualities.P360.value }
            ?: links.firstOrNull()

        bestLink?.let { callback(it) }
    }

    private fun parseQualityFromLabel(label: String): Int {
        return when {
            label.contains("1080", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
