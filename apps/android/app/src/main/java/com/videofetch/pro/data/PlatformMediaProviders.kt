package com.videofetch.pro.data

import com.videofetch.pro.R
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * On-device integrations for public metadata and provider-authorized progressive media URLs.
 * No session cookie is sent to the VideoFetch Pro backend.
 */
internal object PlatformMediaProviders {
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val authenticatedHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun builtIns(): List<LocalMediaProvider> = listOf(
        BilibiliProvider(),
        YouTubeProvider(),
        NiconicoProvider()
    )

    internal class BilibiliProvider : LocalMediaProvider {
        override val providerId = "bilibili"
        override val displayName = "Bilibili"
        override val allowedCookieHosts = setOf("api.bilibili.com")

        override fun canHandle(shareUrl: String): Boolean {
            val host = shareUrl.toHttpUrlOrNull()?.host ?: return false
            return host == "bilibili.com" || host.endsWith(".bilibili.com") ||
                host == "b23.tv" || host.endsWith(".b23.tv")
        }

        override suspend fun resolve(
            shareUrl: String,
            preferredQuality: VideoQualityPreference,
            sessions: EncryptedLocalSessionStore
        ): LocalMediaResolution {
            val input = shareUrl.toHttpUrlOrNull()
                ?: return LocalMediaResolution.Unsupported("Invalid Bilibili URL.")
            val finalUrl = if (input.host == "b23.tv" || input.host.endsWith(".b23.tv")) {
                requestText(shareUrl).finalUrl
            } else input.toString()
            val resolvedUrl = finalUrl.toHttpUrlOrNull() ?: input
            val match = Regex("""(?i)/(?:video/)(BV[0-9A-Za-z]+|av[0-9]+)""")
                .find(resolvedUrl.encodedPath)
                ?: Regex("""(?i)(BV[0-9A-Za-z]+|av[0-9]+)""").find(resolvedUrl.encodedPath)
            val videoId = match?.groupValues?.getOrNull(1)
                ?: return LocalMediaResolution.Unsupported("Cannot find a Bilibili BV/av video ID in this link.")

            val viewUrl = buildUrl("https://api.bilibili.com/x/web-interface/view") {
                addQueryParameter(if (videoId.startsWith("av", true)) "aid" else "bvid", if (videoId.startsWith("av", true)) videoId.drop(2) else videoId)
            }
            val viewJson = JSONObject(
                requestText(viewUrl, sessions, providerId, allowedCookieHosts).body
            )
            val viewCode = viewJson.optInt("code", -1)
            if (viewCode != 0) {
                return LocalMediaResolution.Unsupported(
                    "Bilibili could not return this video (code $viewCode): ${viewJson.optString("message", "request rejected")}"
                )
            }
            val data = viewJson.optJSONObject("data")
                ?: return LocalMediaResolution.Unsupported("Bilibili returned an empty video record.")
            val bvid = data.optString("bvid", videoId)
            val aid = data.optLong("aid")
            val pages = data.optJSONArray("pages")
            val requestedPage = resolvedUrl.queryParameter("p")?.toIntOrNull()?.minus(1) ?: 0
            val pageIndex = requestedPage.coerceIn(0, ((pages?.length() ?: 1) - 1).coerceAtLeast(0))
            val page = pages?.optJSONObject(pageIndex)
            val cid = page?.optLong("cid")?.takeIf { it > 0 } ?: data.optLong("cid")
            val pageTitle = page?.optString("part")?.takeIf { it.isNotBlank() && it != "null" }
            val title = listOfNotNull(data.optString("title").takeIf { it.isNotBlank() }, pageTitle)
                .joinToString(" - ")
                .ifBlank { "Bilibili video $bvid" }
            val owner = data.optJSONObject("owner")?.optString("name")
            val duration = page?.optLong("duration")?.takeIf { it > 0 }
                ?: data.optLong("duration").takeIf { it > 0 }

            val formats = mutableListOf<LocalMediaFormat>()
            if (aid > 0 && cid > 0) {
                val qn = bilibiliQualityCode(preferredQuality)
                val playUrl = buildUrl("https://api.bilibili.com/x/player/playurl") {
                    addQueryParameter("avid", aid.toString())
                    addQueryParameter("cid", cid.toString())
                    addQueryParameter("qn", qn.toString())
                    addQueryParameter("fnval", "0")
                    addQueryParameter("fourk", "1")
                }
                val playJson = runCatching {
                    JSONObject(requestText(playUrl, sessions, providerId, allowedCookieHosts).body)
                }.getOrNull()
                if (playJson?.optInt("code", -1) == 0) {
                    val playData = playJson.optJSONObject("data")
                    val durl = playData?.optJSONArray("durl")
                    // DownloadManager cannot safely join multiple HTTP segments or mux DASH audio/video.
                    if (durl?.length() == 1) {
                        val item = durl.optJSONObject(0)
                        val mediaUrl = item?.optString("url").orEmpty().toHttpUrlOrNull()
                        val formatName = playData?.optString("format").orEmpty().lowercase()
                        val mediaHost = mediaUrl?.host.orEmpty()
                        val trustedCdn = mediaHost.endsWith(".bilivideo.com") ||
                            mediaHost.endsWith(".bilivideo.cn")
                        val isMp4 = formatName.contains("mp4") ||
                            mediaUrl?.encodedPath?.endsWith(".mp4", true) == true
                        if (mediaUrl != null && mediaUrl.isHttps && trustedCdn && isMp4) {
                            val actualQn = playData?.optInt("quality", qn) ?: qn
                            val height = bilibiliHeight(actualQn)
                            val label = bilibiliQualityLabel(actualQn)
                            formats += LocalMediaFormat(
                                id = "bili-$actualQn",
                                label = label,
                                downloadUrl = mediaUrl.toString(),
                                fileName = "$title.mp4",
                                mimeType = "video/mp4",
                                height = height,
                                referer = "https://www.bilibili.com/video/$bvid"
                            )
                        }
                    }
                }
            }

            return LocalMediaResolution.Resolved(
                video = LocalVideoMetadata(
                    title = title,
                    coverUrl = secureBilibiliCover(data.optString("pic")),
                    uploader = owner,
                    durationSeconds = duration,
                    platformName = "Bilibili",
                    formats = formats
                ),
                providerId = providerId,
                allowedHosts = allowedCookieHosts + formats.mapNotNull { it.downloadUrl.toHttpUrlOrNull()?.host }
            )
        }

        private fun secureBilibiliCover(value: String): String? {
            val url = value.toHttpUrlOrNull() ?: return null
            val host = url.host
            if (host != "hdslb.com" && !host.endsWith(".hdslb.com")) return null
            return url.newBuilder().scheme("https").build().toString()
        }

        private fun bilibiliQualityCode(preference: VideoQualityPreference): Int {
            val height = preference.maxHeight ?: 4320
            return when {
                height >= 4320 -> 127
                height >= 2160 -> 120
                height >= 1080 -> 80
                height >= 720 -> 64
                height >= 480 -> 32
                else -> 16
            }
        }

        private fun bilibiliHeight(qn: Int): Int = when (qn) {
            127 -> 4320
            126, 125, 120 -> 2160
            116, 112, 80 -> 1080
            74, 64 -> 720
            32 -> 480
            16 -> 360
            else -> 0
        }.takeIf { it > 0 } ?: 0

        private fun bilibiliQualityLabel(qn: Int): String = when (qn) {
            127 -> "4320p · 8K"
            126 -> "2160p · Dolby Vision"
            125 -> "2160p · HDR"
            120 -> "2160p · 4K"
            116 -> "1080p · 60fps"
            112 -> "1080p+"
            80 -> "1080p"
            74 -> "720p · 60fps"
            64 -> "720p"
            32 -> "480p"
            16 -> "360p"
            else -> "Bilibili quality $qn"
        }
    }

    internal class YouTubeProvider : LocalMediaProvider {
        override val providerId = "youtube"
        override val displayName = "YouTube"
        override val allowedCookieHosts = setOf("www.youtube.com")

        override fun canHandle(shareUrl: String): Boolean {
            val host = shareUrl.toHttpUrlOrNull()?.host ?: return false
            return host == "youtu.be" || host == "youtube.com" ||
                host.endsWith(".youtube.com") || host == "youtube-nocookie.com" ||
                host.endsWith(".youtube-nocookie.com")
        }

        override suspend fun resolve(
            shareUrl: String,
            preferredQuality: VideoQualityPreference,
            sessions: EncryptedLocalSessionStore
        ): LocalMediaResolution {
            val source = shareUrl.toHttpUrlOrNull()
                ?: return LocalMediaResolution.Unsupported("Invalid YouTube URL.")
            val id = when {
                source.host == "youtu.be" -> source.pathSegments.firstOrNull()
                source.queryParameter("v") != null -> source.queryParameter("v")
                else -> source.pathSegments.dropWhile { it !in setOf("shorts", "embed", "live") }
                    .drop(1).firstOrNull()
            }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
                ?: return LocalMediaResolution.Unsupported("Cannot find a YouTube video ID in this link.")

            val oembed = buildUrl("https://www.youtube.com/oembed") {
                addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
                addQueryParameter("format", "json")
            }
            val metadata = JSONObject(requestText(oembed).body)
            val watchHtml = runCatching {
                requestText("https://www.youtube.com/watch?v=$id").body
            }.getOrNull()
            val seconds = watchHtml?.let {
                Regex("\"lengthSeconds\"\\s*:\\s*\"(\\d+)\"")
                    .find(it)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            return LocalMediaResolution.Resolved(
                video = LocalVideoMetadata(
                    title = metadata.optString("title").ifBlank { "YouTube video $id" },
                    coverUrl = metadata.optString("thumbnail_url").takeIf { it.startsWith("https://") },
                    uploader = metadata.optString("author_name").takeIf { it.isNotBlank() },
                    durationSeconds = seconds,
                    platformName = "YouTube",
                    formats = emptyList(),
                    downloadNoteResId = R.string.youtube_download_note
                ),
                providerId = providerId,
                allowedHosts = allowedCookieHosts
            )
        }
    }

    internal class NiconicoProvider : LocalMediaProvider {
        override val providerId = "niconico"
        override val displayName = "Niconico"
        override val allowedCookieHosts = setOf("www.nicovideo.jp")

        override fun canHandle(shareUrl: String): Boolean {
            val host = shareUrl.toHttpUrlOrNull()?.host ?: return false
            return host == "nicovideo.jp" || host.endsWith(".nicovideo.jp") ||
                host == "nico.ms" || host.endsWith(".nico.ms")
        }

        override suspend fun resolve(
            shareUrl: String,
            preferredQuality: VideoQualityPreference,
            sessions: EncryptedLocalSessionStore
        ): LocalMediaResolution {
            val source = shareUrl.toHttpUrlOrNull()
                ?: return LocalMediaResolution.Unsupported("Invalid Niconico URL.")
            val watchUrl = if (source.host == "nico.ms" || source.host.endsWith(".nico.ms")) {
                requestText(shareUrl).finalUrl
            } else source.toString()
            val watch = watchUrl.toHttpUrlOrNull() ?: source
            val videoId = Regex("""(?i)(sm|so|nm)\d+""")
                .find(watch.encodedPath)?.value
                ?: return LocalMediaResolution.Unsupported("Cannot find a Niconico video ID in this link.")

            val actionTrackId = UUID.randomUUID().toString().replace("-", "").take(10) +
                "_" + System.currentTimeMillis().toString()
            val headers = mapOf("X-Frontend-Id" to "6", "X-Frontend-Version" to "0")
            val hasLocalSession = sessions.hasCookie(providerId)
            fun watchApi(loggedIn: Boolean): JSONObject {
                val endpoint = buildUrl(
                    "https://www.nicovideo.jp/api/watch/" +
                        (if (loggedIn) "v3/" else "v3_guest/") + videoId
                ) {
                    addQueryParameter("actionTrackId", actionTrackId)
                    addQueryParameter("noSideEffect", "true")
                }
                val response = requestText(
                    endpoint,
                    if (loggedIn) sessions else null,
                    providerId,
                    allowedCookieHosts,
                    headers
                ).body
                val json = JSONObject(response)
                val status = json.optJSONObject("meta")?.optInt("status", 200) ?: 200
                if (status != 200 || json.optJSONObject("data") == null) {
                    throw IOException("Niconico watch API returned status $status.")
                }
                return json
            }

            val watchJson = runCatching { watchApi(hasLocalSession) }.getOrElse { firstError ->
                if (!hasLocalSession) throw firstError
                runCatching { watchApi(false) }.getOrElse { throw firstError }
            }
            val data = watchJson.optJSONObject("data")
                ?: return LocalMediaResolution.Unsupported("Niconico returned an empty video record.")
            val video = data.optJSONObject("video")
                ?: return LocalMediaResolution.Unsupported("Niconico did not return video metadata.")
            val movie = data.optJSONObject("media")
                ?.optJSONObject("delivery")
                ?.optJSONObject("movie")
            val availableVariants = buildList {
                val videos = movie?.optJSONArray("videos") ?: return@buildList
                for (index in 0 until videos.length()) {
                    val item = videos.optJSONObject(index) ?: continue
                    if (!item.optBoolean("isAvailable", false)) continue
                    val metadata = item.optJSONObject("metadata")
                    val label = metadata?.optString("label").orEmpty()
                    val height = metadata?.optJSONObject("resolution")?.optInt("height", 0) ?: 0
                    val resolvedLabel = label.takeIf { it.isNotBlank() && it != "null" }
                        ?: height.takeIf { it > 0 }?.let { "${it}p" }
                    if (resolvedLabel != null && resolvedLabel !in this) add(resolvedLabel)
                }
            }
            return LocalMediaResolution.Resolved(
                video = LocalVideoMetadata(
                    title = video.optString("title").ifBlank { "Niconico video $videoId" },
                    coverUrl = video.optJSONObject("thumbnail")?.optString("url")
                        ?.takeIf { it.startsWith("https://") },
                    uploader = data.optJSONObject("owner")?.optString("nickname")
                        ?.takeIf { it.isNotBlank() },
                    durationSeconds = video.optLong("duration", 0L).takeIf { it > 0 },
                    platformName = "Niconico",
                    formats = emptyList(),
                    downloadNoteResId = R.string.niconico_download_note,
                    availableVariantLabels = availableVariants
                ),
                providerId = providerId,
                allowedHosts = allowedCookieHosts
            )
        }
    }

    private data class HttpResult(val body: String, val finalUrl: String)

    private fun requestText(
        url: String,
        sessions: EncryptedLocalSessionStore? = null,
        providerId: String? = null,
        allowedHosts: Set<String> = emptySet(),
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7")
        val localCookie = if (sessions != null && providerId != null) {
            sessions.cookieHeaderFor(providerId, url, allowedHosts)
        } else null
        localCookie?.let { builder.header("Cookie", it) }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val requestClient = if (localCookie != null) authenticatedHttp else http
        requestClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Provider request failed (HTTP ${response.code}).")
            val body = response.body?.string() ?: throw IOException("Provider returned an empty response.")
            return HttpResult(body, response.request.url.toString())
        }
    }

    private fun buildUrl(base: String, configure: HttpUrl.Builder.() -> Unit): String {
        val url = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid provider endpoint")
        return url.newBuilder().apply(configure).build().toString()
    }

    private fun extractMeta(html: String, property: String): String? {
        val escaped = Regex.escape(property)
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']$escaped["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']$escaped["']""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { pattern -> pattern.find(html)?.groupValues?.getOrNull(1) }
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
    }
}
