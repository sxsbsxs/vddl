package com.videofetch.pro.data

import android.content.Context

enum class VideoQualityPreference(val storageValue: String, val label: String) {
    BEST("best", "最高可用"),
    Q2160("2160", "2160p"),
    Q1440("1440", "1440p"),
    Q1080("1080", "1080p"),
    Q720("720", "720p"),
    Q480("480", "480p");

    companion object {
        fun fromStorage(value: String?): VideoQualityPreference =
            entries.firstOrNull { it.storageValue == value } ?: BEST
    }
}

class VideoQualityPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("videofetch_preferences", Context.MODE_PRIVATE)

    fun get(): VideoQualityPreference =
        VideoQualityPreference.fromStorage(preferences.getString(KEY_QUALITY, VideoQualityPreference.BEST.storageValue))

    fun set(value: VideoQualityPreference) {
        preferences.edit().putString(KEY_QUALITY, value.storageValue).apply()
    }

    private companion object {
        const val KEY_QUALITY = "preferred_video_quality"
    }
}
