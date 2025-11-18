package com.filmapik

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class EfekStream : ExtractorApi() {
    override val name = "EfekStream"
    override val mainUrl = "https://v2.efek.stream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (Any) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = fetch(url, referer = mainUrl).document
        val script = doc.select("script:contains(sources)").html()

        val json = Regex("""sources\s*:\s*(\[[^\]]+])""")
            .find(script)?.groupValues?.get(1)
            ?: return

        val arr = JSONObject("{\"sources\":$json}").getJSONArray("sources")

        for (i in 0 until arr.length()) {
            val file = arr.getJSONObject(i).getString("file")

            val quality = when {
                file.contains("1080") -> Qualities.P1080.value
                file.contains("720") -> Qualities.P720.value
                file.contains("480") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            callback(
                newExtractorLink(
                    name = name,
                    text = "$name - ${quality}p",
                    url = file,
                    referer = url,
                    quality = quality,
                    isM3u8 = file.endsWith(".m3u8"),
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0"
                    )
                )
            )
        }
    }
}
