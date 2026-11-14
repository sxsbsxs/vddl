package com.videofetch.pro.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts Bilibili's single-file DASH representation into a standards-compliant
 * on-demand MPD. A .m4s URL by itself is not a playable file: its initialization
 * and SIDX byte ranges are required for ExoPlayer to read it correctly.
 */
internal object BilibiliMediaFormatParser {
    fun parse(playData: JSONObject, maxHeight: Int?, durationSeconds: Long): List<LocalMediaFormat> {
        val formats = mutableListOf<LocalMediaFormat>()
        val dash = playData.optJSONObject("dash")
        if (dash != null && durationSeconds > 0) {
            val videos = entries(dash.optJSONArray("video")).mapNotNull { item ->
                val url = safeMediaUrl(value(item, "baseUrl", "base_url")) ?: return@mapNotNull null
                val height = item.optInt("height", 0)
                if (height <= 0) return@mapNotNull null
                Triple(item, url, height)
            }
            val selectedVideo = chooseVideo(videos, maxHeight)
            val audios = entries(dash.optJSONArray("audio")).mapNotNull { item ->
                val url = safeMediaUrl(value(item, "baseUrl", "base_url")) ?: return@mapNotNull null
                item to url
            }
            val selectedAudio = audios.maxByOrNull { it.first.optLong("bandwidth", 0L) }
            if (selectedVideo != null && selectedAudio != null) {
                val (video, videoUrl, height) = selectedVideo
                val audio = selectedAudio.first
                val videoInit = segmentRange(video, true)
                val videoIndex = segmentRange(video, false)
                val audioInit = segmentRange(audio, true)
                val audioIndex = segmentRange(audio, false)
                if (videoInit != null && videoIndex != null && audioInit != null && audioIndex != null) {
                    val fps = value(video, "frameRate", "frame_rate").substringBefore('.').toIntOrNull()
                    val videoCodec = video.optString("codecs").takeIf { it.isNotBlank() }
                    val audioCodec = audio.optString("codecs").takeIf { it.isNotBlank() }
                    val mpd = buildMpd(
                        durationSeconds = durationSeconds,
                        videoUrl = videoUrl,
                        video = video,
                        videoInit = videoInit,
                        videoIndex = videoIndex,
                        audioUrl = selectedAudio.second,
                        audio = audio,
                        audioInit = audioInit,
                        audioIndex = audioIndex
                    )
                    formats += LocalMediaFormat(
                        id = "bili-dash-${video.optInt("id", height)}",
                        label = listOfNotNull(
                            "${height}p",
                            fps?.takeIf { it > 0 }?.let { "${it}fps" },
                            "音视频自动合并"
                        ).joinToString(" · "),
                        downloadUrl = videoUrl,
                        fileName = "videofetch-video.mp4",
                        mimeType = "video/mp4",
                        height = height,
                        frameRate = fps,
                        codec = videoCodec,
                        audioLabel = audioCodec,
                        videoSegmentUrls = listOf(videoUrl),
                        dashManifest = mpd
                    )
                }
            }
        }

        // Progressive MP4 is a fallback when the API does not provide complete DASH range metadata.
        val durl = playData.optJSONArray("durl")
        if (durl != null && durl.length() in 1..80 && playData.optString("format").contains("mp4", true)) {
            val segments = (0 until durl.length()).mapNotNull { index ->
                val item = durl.optJSONObject(index) ?: return@mapNotNull null
                safeMediaUrl(item.optString("url"))?.takeIf {
                    it.toHttpUrlOrNull()?.encodedPath?.endsWith(".mp4", true) == true
                }
            }
            if (segments.size == durl.length()) {
                val quality = playData.optInt("quality", 0)
                val height = when (quality) {
                    127 -> 4320
                    126, 125, 120 -> 2160
                    116, 112, 80 -> 1080
                    74, 64 -> 720
                    32 -> 480
                    16 -> 360
                    else -> 0
                }
                formats += LocalMediaFormat(
                    id = "bili-mp4-$quality-${segments.size}",
                    label = if (segments.size > 1) "MP4 · ${segments.size} 段合并"
                        else "MP4 · ${height.takeIf { it > 0 }?.let { "${it}p" } ?: "平台原画"}",
                    downloadUrl = segments.first(),
                    fileName = "videofetch-video.mp4",
                    mimeType = "video/mp4",
                    height = height,
                    videoSegmentUrls = segments
                )
            }
        }
        return formats.distinctBy { it.id }
    }

    private fun buildMpd(
        durationSeconds: Long,
        videoUrl: String,
        video: JSONObject,
        videoInit: String,
        videoIndex: String,
        audioUrl: String,
        audio: JSONObject,
        audioInit: String,
        audioIndex: String
    ): String {
        val duration = "PT${durationSeconds}S"
        val videoBandwidth = video.optLong("bandwidth", 1L).coerceAtLeast(1L)
        val audioBandwidth = audio.optLong("bandwidth", 1L).coerceAtLeast(1L)
        val videoMime = video.optString("mimeType").takeIf { it == "video/mp4" } ?: "video/mp4"
        val audioMime = audio.optString("mimeType").takeIf { it == "audio/mp4" } ?: "audio/mp4"
        val videoAttrs = buildString {
            append("id=\"${xml(video.optString("id").ifBlank { video.optInt("height").toString() })}\" ")
            append("bandwidth=\"${videoBandwidth}\" ")
            append("width=\"${video.optInt("width", 0)}\" ")
            append("height=\"${video.optInt("height", 0)}\" ")
            value(video, "frameRate", "frame_rate").takeIf { it.isNotBlank() }?.let {
                append("frameRate=\"${xml(it)}\" ")
            }
            video.optString("codecs").takeIf { it.isNotBlank() }?.let {
                append("codecs=\"${xml(it)}\" ")
            }
        }
        val audioAttrs = buildString {
            append("id=\"${xml(audio.optString("id").ifBlank { "audio" })}\" ")
            append("bandwidth=\"${audioBandwidth}\" ")
            audio.optString("codecs").takeIf { it.isNotBlank() }?.let {
                append("codecs=\"${xml(it)}\" ")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" minBufferTime="PT1.5S" mediaPresentationDuration="$duration">
  <Period id="0" duration="$duration">
    <AdaptationSet id="1" contentType="video" mimeType="$videoMime" segmentAlignment="true" startWithSAP="1">
      <Representation $videoAttrs>
        <BaseURL>${xml(videoUrl)}</BaseURL>
        <SegmentBase indexRange="$videoIndex" indexRangeExact="true"><Initialization range="$videoInit"/></SegmentBase>
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="2" contentType="audio" mimeType="$audioMime" segmentAlignment="true">
      <Representation $audioAttrs>
        <BaseURL>${xml(audioUrl)}</BaseURL>
        <SegmentBase indexRange="$audioIndex" indexRangeExact="true"><Initialization range="$audioInit"/></SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
    }

    private fun segmentRange(item: JSONObject, initialization: Boolean): String? {
        val segmentBase = item.optJSONObject("SegmentBase") ?: item.optJSONObject("segment_base") ?: return null
        val range = if (initialization) {
            value(segmentBase, "Initialization", "initialization")
        } else {
            value(segmentBase, "indexRange", "index_range")
        }
        if (!range.matches(Regex("\\d+-\\d+"))) return null
        val (start, end) = range.split('-').mapNotNull { it.toLongOrNull() }.let {
            if (it.size == 2) it else return null
        }
        return range.takeIf { start >= 0 && end >= start }
    }

    private fun chooseVideo(
        candidates: List<Triple<JSONObject, String, Int>>,
        maxHeight: Int?
    ): Triple<JSONObject, String, Int>? {
        if (candidates.isEmpty()) return null
        if (maxHeight == null) return candidates.maxByOrNull { it.third }
        return candidates.filter { it.third <= maxHeight }.maxByOrNull { it.third }
            ?: candidates.minByOrNull { it.third }
    }

    private fun entries(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun value(item: JSONObject, primary: String, alternate: String): String =
        item.optString(primary).takeIf { it.isNotBlank() && it != "null" }
            ?: item.optString(alternate).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun safeMediaUrl(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        val host = url.host
        val trusted = host == "bilivideo.com" || host.endsWith(".bilivideo.com") ||
            host == "bilivideo.cn" || host.endsWith(".bilivideo.cn")
        return url.toString().takeIf { url.isHttps && trusted && url.username.isEmpty() && url.password.isEmpty() }
    }
}
