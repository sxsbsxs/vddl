package com.videofetch.pro.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object BilibiliMediaFormatParser {
    fun parse(playData: JSONObject, maxHeight: Int?): List<LocalMediaFormat> {
        val formats = mutableListOf<LocalMediaFormat>()
        val dash = playData.optJSONObject("dash")
        if (dash != null) {
            val videos = entries(dash.optJSONArray("video")).mapNotNull { item ->
                val url = safeMediaUrl(item.optString("baseUrl").ifBlank { item.optString("base_url") }) ?: return@mapNotNull null
                val height = item.optInt("height", 0)
                if (height <= 0) return@mapNotNull null
                Triple(item, url, height)
            }
            val video = chooseVideo(videos, maxHeight)
            val audios = entries(dash.optJSONArray("audio")).mapNotNull { item ->
                val url = safeMediaUrl(item.optString("baseUrl").ifBlank { item.optString("base_url") }) ?: return@mapNotNull null
                item to url
            }
            val audio = audios.maxByOrNull { it.first.optLong("bandwidth", 0L) }
            if (video != null && audio != null) {
                val (item, videoUrl, height) = video
                val fps = item.optString("frameRate").substringBefore('.').toIntOrNull()
                    ?: item.optString("frame_rate").substringBefore('.').toIntOrNull()
                val qualityId = item.optInt("id", height)
                val label = listOfNotNull(
                    "${height}p", fps?.takeIf { it > 0 }?.let { "${it}fps" }, "音视频合并"
                ).joinToString(" · ")
                formats += LocalMediaFormat(
                    id = "bili-dash-$qualityId", label = label, downloadUrl = videoUrl,
                    fileName = "videofetch-video.mp4", mimeType = "video/mp4", height = height, frameRate = fps,
                    codec = item.optString("codecs").takeIf { it.isNotBlank() },
                    audioLabel = audio.first.optString("codecs").takeIf { it.isNotBlank() },
                    videoSegmentUrls = listOf(videoUrl), audioUrl = audio.second
                )
            }
        }
        val durl = playData.optJSONArray("durl")
        if (durl != null && durl.length() in 1..80 && playData.optString("format").contains("mp4", true)) {
            val segments = (0 until durl.length()).mapNotNull { index ->
                val item = durl.optJSONObject(index) ?: return@mapNotNull null
                safeMediaUrl(item.optString("url"))?.takeIf { it.toHttpUrlOrNull()?.encodedPath?.endsWith(".mp4", true) == true }
            }
            if (segments.size == durl.length()) {
                val qn = playData.optInt("quality", 0)
                val height = when (qn) {
                    127 -> 4320
                    126, 125, 120 -> 2160
                    116, 112, 80 -> 1080
                    74, 64 -> 720
                    32 -> 480
                    16 -> 360
                    else -> 0
                }
                val label = if (segments.size > 1) "MP4 · ${segments.size} 段合并"
                    else "MP4 · ${height.takeIf { it > 0 }?.let { "${it}p" } ?: "平台原画"}"
                formats += LocalMediaFormat(
                    id = "bili-mp4-$qn-${segments.size}", label = label, downloadUrl = segments.first(),
                    fileName = "videofetch-video.mp4", mimeType = "video/mp4", height = height,
                    videoSegmentUrls = segments
                )
            }
        }
        return formats.distinctBy { it.id }
    }

    private fun chooseVideo(candidates: List<Triple<JSONObject, String, Int>>, maxHeight: Int?): Triple<JSONObject, String, Int>? {
        if (candidates.isEmpty()) return null
        if (maxHeight == null) return candidates.maxByOrNull { it.third }
        return candidates.filter { it.third <= maxHeight }.maxByOrNull { it.third } ?: candidates.minByOrNull { it.third }
    }

    private fun entries(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun safeMediaUrl(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        val host = url.host
        val trusted = host == "bilivideo.com" || host.endsWith(".bilivideo.com") ||
            host == "bilivideo.cn" || host.endsWith(".bilivideo.cn")
        return url.toString().takeIf { url.isHttps && trusted && url.username.isEmpty() && url.password.isEmpty() }
    }
}
