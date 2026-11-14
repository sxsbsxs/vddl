package com.videofetch.pro.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(UnstableApi::class)
class MediaMergeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return failure("Missing download job")
        val jobFile = File(applicationContext.filesDir, "videofetch-download-jobs/$jobId.json")
        if (!jobFile.isFile) return failure("Download job data is missing")
        val directory = File(
            applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: File(applicationContext.filesDir, "Movies"), "VideoFetch Pro"
        )
        if (!directory.exists() && !directory.mkdirs()) return failureAndClean(jobFile, "Cannot create output folder")
        val title = safeFileName(inputData.getString(KEY_TITLE) ?: "videofetch-video.mp4")
        val baseName = if (title.endsWith(".mp4", true)) title else "$title.mp4"
        val stem = baseName.removeSuffix(".mp4")
        val output = File(directory, "$stem-${jobId.take(8)}.mp4")
        val temp = File(directory, ".$jobId.partial.mp4")
        try {
            val spec = JSONObject(jobFile.readText())
            val urlsJson = spec.optJSONArray("videoUrls") ?: return failureAndClean(jobFile, "No video streams")
            val videoUrls = (0 until urlsJson.length()).map { urlsJson.getString(it) }
            if (videoUrls.isEmpty() || videoUrls.size > 80) return failureAndClean(jobFile, "Invalid video segments")
            val audioUrl = spec.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" }
            val dashManifest = spec.optString("dashManifest").takeIf { it.isNotBlank() && it != "null" }
            val referer = spec.optString("referer").takeIf { it.isNotBlank() && it != "null" }
            (videoUrls + listOfNotNull(audioUrl)).forEach(::requireSafeHttpsUrl)
            val manifestUrls = dashManifest?.let { manifest ->
                Regex("<BaseURL>(.*?)</BaseURL>").findAll(manifest).map {
                    it.groupValues[1].replace("&amp;", "&")
                }.toList()
            }.orEmpty()
            if (dashManifest != null) {
                require(manifestUrls.size >= 2) { "DASH manifest must include video and audio tracks" }
                manifestUrls.forEach(::requireSafeHttpsUrl)
            }
            referer?.let(::requireSafeHttpsUrl)
            var manifestFile: File? = null
            val headers = buildMap {
                put("User-Agent", USER_AGENT)
                referer?.let { put("Referer", it) }
            }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT).setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
            val dataSource: DataSource.Factory = DefaultDataSource.Factory(applicationContext, http)
            val sourceFactory = DefaultMediaSourceFactory(dataSource)
            val bitmapLoader = DataSourceBitmapLoader.Builder(applicationContext).setDataSourceFactory(dataSource).build()
            val assetLoader = DefaultAssetLoaderFactory(
                applicationContext, DefaultDecoderFactory.Builder(applicationContext).build(),
                Clock.DEFAULT, sourceFactory, bitmapLoader
            )
            val composition = if (dashManifest != null) {
                // A standalone .m4s URL is not enough: use the SegmentBase initialization and SIDX ranges in the MPD.
                val mpdFile = File(jobFile.parentFile, "$jobId.mpd")
                manifestFile = mpdFile
                mpdFile.writeText(dashManifest)
                val dashItem = MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(mpdFile))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                Composition.Builder(
                    EditedMediaItemSequence.withAudioAndVideoFrom(
                        listOf(EditedMediaItem.Builder(dashItem).build())
                    )
                ).build()
            } else {
                val videoItems = videoUrls.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
                if (audioUrl != null) {
                    val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUrl)).build()
                    Composition.Builder(
                        EditedMediaItemSequence.withVideoFrom(videoItems),
                        EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
                    ).build()
                } else Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(videoItems)).build()
            }
            setForeground(foregroundInfo(0))
            temp.delete()
            val completion = CompletableDeferred<Throwable?>()
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { completion.complete(null) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    completion.complete(exportException)
                }
            }
            val transformer = withContext(Dispatchers.Main.immediate) {
                Transformer.Builder(applicationContext).setLooper(Looper.getMainLooper())
                    .setAssetLoaderFactory(assetLoader).addListener(listener).build()
            }
            try {
                withContext(Dispatchers.Main.immediate) { transformer.start(composition, temp.absolutePath) }
                while (!completion.isCompleted) {
                    val progress = withContext(Dispatchers.Main.immediate) {
                        val holder = ProgressHolder()
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
                    }
                    if (progress != null) {
                        setProgress(workDataOf(KEY_PROGRESS to progress.coerceIn(0, 99)))
                        setForeground(foregroundInfo(progress))
                    }
                    delay(750)
                }
                completion.await()?.let { throw it }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    if (!completion.isCompleted) transformer.cancel()
                    transformer.removeAllListeners()
                }
            }
            if (!temp.isFile || temp.length() == 0L) throw IOException("Merged file is empty")
            if (output.exists() && !output.delete()) throw IOException("Cannot replace existing output")
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            manifestFile?.delete()
            jobFile.delete()
            setProgress(workDataOf(KEY_PROGRESS to 100))
            return Result.success(workDataOf(KEY_OUTPUT_PATH to output.absolutePath, KEY_TITLE to title))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            temp.delete()
            throw cancelled
        } catch (error: Throwable) {
            temp.delete()
            manifestFile?.delete()
            if (error.hasIoCause() && runAttemptCount < 5) return Result.retry()
            jobFile.delete()
            return Result.failure(workDataOf(
                KEY_ERROR to (error.message?.take(240) ?: "Could not merge video and audio"),
                KEY_TITLE to title
            ))
        }
    }

    private fun foregroundInfo(progress: Int): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(com.videofetch.pro.R.string.app_name))
            .setContentText(applicationContext.getString(com.videofetch.pro.R.string.download_merging_notification))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id.hashCode() and 0x7fffffff, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode() and 0x7fffffff, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null)
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun requireSafeHttpsUrl(value: String) {
        val url = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid media URL")
        val host = url.host
        val trustedMediaHost = host.endsWith(".bilivideo.com") || host == "bilivideo.com" ||
            host.endsWith(".bilivideo.cn") || host == "bilivideo.cn"
        val trustedReferer = host == "www.bilibili.com"
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (trustedMediaHost || trustedReferer)
        ) { "Only provider-approved HTTPS hosts are accepted" }
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(110)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "videofetch-video.mp4"
    }

    private fun Throwable.hasIoCause(): Boolean {
        var current: Throwable? = this
        repeat(8) {
            if (current is IOException) return true
            current = current?.cause
        }
        return false
    }

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))
    private fun failureAndClean(file: File, message: String): Result { file.delete(); return failure(message) }

    companion object {
        const val KEY_JOB_ID = "merge_job_id"
        const val KEY_TITLE = "merge_title"
        const val KEY_PROGRESS = "merge_progress"
        const val KEY_OUTPUT_PATH = "merge_output_path"
        const val KEY_ERROR = "merge_error"
        private const val CHANNEL_ID = "videofetch_media_downloads"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
