package com.dramaindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.Base64

object Extractors {
    private val miteDrive = MiteDrive()
    private val miteDrive2 = MiteDrive2()
    private val berkasDrive = BerkasDrive()

    suspend fun loadAllLinks(
        urls: List<String>,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        urls.map { url ->
            async {
                runCatching {
                    when {
                        "mitedrive.my.id" in url -> miteDrive2.getUrl(url, referer, subtitleCallback, callback)
                        "mitedrive" in url -> miteDrive.getUrl(url, referer, subtitleCallback, callback)
                        "berkasdrive" in url -> berkasDrive.getUrl(url, referer, subtitleCallback, callback)
                    }
                }
            }
        }.awaitAll()
    }
}

class MiteDrive : ExtractorApi() {
    override val name = "MiteDrive"
    override val mainUrl = "https://mitedrive.com"
    override val requiresReferer = true

    private fun encodeBase64Twice(str: String): String {
        val encoder = Base64.getEncoder()
        var encoded = encoder.encodeToString(str.toByteArray(Charsets.UTF_8))
        encoded = encoder.encodeToString(encoded.toByteArray(Charsets.UTF_8))
        return encoded
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val ip = app.get("https://ipv4.icanhazip.com").text.trim()
        val payload = JSONObject().apply {
            put("ip", ip)
            put("device", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            put("browser", "Mozilla")
            put("cookie", "")
            put("referrer", referer ?: "")
        }
        val csrfToken = encodeBase64Twice(payload.toString())
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/view/"
        val params = mapOf("csrf_token" to csrfToken, "slug" to slug)
        val response = app.post(apiUrl, data = params)
        val videoUrl = JSONObject(response.text).optString("url")
        if (videoUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    url = videoUrl,
                    name = "720p",
                    source = name
                )
            )
        }
    }
}

class MiteDrive2 : ExtractorApi() {
    override val name = "MiteDrive2"
    override val mainUrl = "https://mitedrive.my.id"
    override val requiresReferer = true

    private fun encodeBase64Twice(str: String): String {
        val encoder = Base64.getEncoder()
        var encoded = encoder.encodeToString(str.toByteArray(Charsets.UTF_8))
        encoded = encoder.encodeToString(encoded.toByteArray(Charsets.UTF_8))
        return encoded
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val ip = app.get("https://ipv4.icanhazip.com").text.trim()
        val payload = JSONObject().apply {
            put("ip", ip)
            put("device", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            put("browser", "Mozilla")
            put("cookie", "")
            put("referrer", referer ?: "")
        }
        val csrfToken = encodeBase64Twice(payload.toString())
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/view/"
        val params = mapOf("csrf_token" to csrfToken, "slug" to slug)
        val response = app.post(apiUrl, data = params)
        val videoUrl = JSONObject(response.text).optString("url")
        if (videoUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    url = videoUrl,
                    name = "720p",
                    source = name
                )
            )
        }
    }
}

class BerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = true

    private fun getEmbedUrl(url: String): String =
        if (url.contains("/streaming/")) url else "$mainUrl/streaming/?id=${url.substringAfter("?id=")}"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val embedUrl = getEmbedUrl(url)
        val doc = app.get(embedUrl, referer = referer).document
        doc.select(".daftar_server li[data-url]").forEach { element ->
            val serverUrl = element.attr("data-url")
            val sourceName = when {
                serverUrl.contains("miterequest") -> "MiteReq"
                serverUrl.contains("cdn-cf.berkasdrive") -> "BerkasCF"
                serverUrl.contains("cdn-bunny.berkasdrive") -> "BunnyDrive"
                else -> name
            }
            callback(
                newExtractorLink(
                    url = serverUrl,
                    name = sourceName,
                    source = name
                )
            )
        }
    }
}
