package com.videofetch.pro.data

import android.content.Context
import com.videofetch.pro.R

/**
 * Resolution ceiling requested by the user. Provider adapters must still return
 * only formats actually offered to this account for the source video.
 */
enum class VideoQualityPreference(val storageValue: String, val labelRes: Int, val maxHeight: Int?) {
    BEST("best", R.string.quality_best, null),
    Q4320("4320", R.string.quality_4320, 4320),
    Q2880("2880", R.string.quality_2880, 2880),
    Q2160("2160", R.string.quality_2160, 2160),
    Q1800("1800", R.string.quality_1800, 1800),
    Q1440("1440", R.string.quality_1440, 1440),
    Q1200("1200", R.string.quality_1200, 1200),
    Q1080("1080", R.string.quality_1080, 1080),
    Q960("960", R.string.quality_960, 960),
    Q900("900", R.string.quality_900, 900),
    Q864("864", R.string.quality_864, 864),
    Q720("720", R.string.quality_720, 720),
    Q640("640", R.string.quality_640, 640),
    Q576("576", R.string.quality_576, 576),
    Q540("540", R.string.quality_540, 540),
    Q480("480", R.string.quality_480, 480),
    Q432("432", R.string.quality_432, 432),
    Q360("360", R.string.quality_360, 360),
    Q288("288", R.string.quality_288, 288),
    Q240("240", R.string.quality_240, 240),
    Q144("144", R.string.quality_144, 144);

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
