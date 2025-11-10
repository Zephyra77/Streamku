package com.efek

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink

class EfekStreamExtractor : ExtractorApi() {
    override var name = "EfekStream"
    override var mainUrl = "https://fa.efek.stream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: mainUrl),
        )

        val res = app.get(url, referer = referer, headers = headers)
        var text = res.text

        fun findJwSetup(src: String): String? {
            val r = Regex("jwplayer\\.setup\\s*\\((\\{[\\s\\S]*?\\})\\)", RegexOption.IGNORE_CASE)
            return r.find(src)?.groupValues?.get(1)
        }

        var jwJson = findJwSetup(text)

        if (jwJson == null) {
            val scriptUrls = Regex("src=[\"'](https?://[^\"']+\\.js)[\"']", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()

            for (s in scriptUrls) {
                try {
                    val sTxt = app.get(s, referer = url, headers = headers).text
                    text += "\n" + sTxt
                    jwJson = findJwSetup(sTxt) ?: jwJson
                    if (jwJson != null) break
                } catch (_: Exception) {}
            }
        }

        val found = LinkedHashSet<String>()

        fun extractUrls(src: String) {
            Regex("https?://[^\"'\\s]+\\.m3u8", RegexOption.IGNORE_CASE).findAll(src).forEach {
                found.add(it.value)
            }
            Regex("https?://[^\"'\\s]+\\.(?:mp4|mp3)", RegexOption.IGNORE_CASE).findAll(src).forEach {
                found.add(it.value)
            }
            Regex("https?:\\\\?/\\\\?/[A-Za-z0-9\\\\/._%:-]+\\.m3u8", RegexOption.IGNORE_CASE).findAll(src).forEach {
                found.add(it.value.replace("\\/", "/"))
            }
            Regex("https?:\\\\?/\\\\?/[A-Za-z0-9\\\\/._%:-]+\\.(?:mp4|mp3)", RegexOption.IGNORE_CASE).findAll(src).forEach {
                found.add(it.value.replace("\\/", "/"))
            }
        }

        if (jwJson != null) {
            extractUrls(jwJson)
            Regex("\\\"tracks\\\":\\s*\\[([\\s\\S]*?)\\]", RegexOption.IGNORE_CASE).find(jwJson)?.groupValues?.get(1)?.let { tracksBlock ->
                Regex("https?://[^\"'\\s]+\\.(?:vtt|srt)", RegexOption.IGNORE_CASE).findAll(tracksBlock).forEach { m ->
                    subtitleCallback.invoke(SubtitleFile("", m.value))
                }
                Regex("https?:\\\\?/\\\\?/[A-Za-z0-9\\\\/._%:-]+\\.(?:vtt|srt)", RegexOption.IGNORE_CASE).findAll(tracksBlock).forEach { m ->
                    subtitleCallback.invoke(SubtitleFile("", m.value.replace("\\/", "/")))
                }
            }
        }

        extractUrls(text)

        if (found.isEmpty()) {
            try {
                val idMatch = Regex("/(?:e|v|download|file)/([A-Za-z0-9_-]+)").find(url)
                val id = idMatch?.groupValues?.get(1)
                if (!id.isNullOrEmpty()) {
                    val apiCandidates = listOf(
                        "https://filemoon.in/api/source/$id",
                        "https://fa.efek.stream/api/source/$id",
                        "https://v2.efek.stream/api/source/$id"
                    )
                    for (api in apiCandidates) {
                        try {
                            val apiRes = app.post(api, referer = url, data = mapOf("r" to "", "d" to url), headers = headers).text
                            extractUrls(apiRes)
                            if (found.isNotEmpty()) break
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        if (found.isNotEmpty()) {
            val m3u8s = found.filter { it.contains(".m3u8") }
            if (m3u8s.isNotEmpty()) {
                for (m in m3u8s.distinct()) {
                    generateM3u8(name, m, referer = url, headers = headers).forEach(callback)
                }
            }

            val others = found.filter { !it.contains(".m3u8") }
            for (o in others.distinct()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url = o,
                        ExtractorLinkType.Video
                    ) {
                        this.headers = headers
                    }
                )
            }
        }
    }
}
