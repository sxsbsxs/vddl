package com.videofetch.pro.data

import android.content.Context
import com.videofetch.pro.R

enum class VideoQualityPreference(val storageValue: String, val labelRes: Int) {
    BEST("best", R.string.quality_best),
    Q2160("2160", R.string.quality_2160),
    Q1440("1440", R.string.quality_1440),
    Q1080("1080", R.string.quality_1080),
    Q720("720", R.string.quality_720),
    Q480("480", R.string.quality_480);

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
