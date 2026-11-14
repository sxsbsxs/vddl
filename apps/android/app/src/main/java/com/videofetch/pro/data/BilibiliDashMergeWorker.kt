package com.videofetch.pro.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Persistent foreground-capable DASH downloader. Audio and video are fetched
 * independently and muxed into one MP4 using Android's native media APIs.
 */
class BilibiliDashMergeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result {
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return failure("Missing video stream URL")
        val audioUrl = inputData.getString(KEY_AUDIO_URL) ?: return failure("Missing audio stream URL")
        val referer = inputData.getString(KEY_REFERER).orEmpty()
        val fileName = safeFileName(inputData.getString(KEY_FILE_NAME) ?: "videofetch-video.mp4")
        val workDir = File(applicationContext.cacheDir, "dash-$id").apply { mkdirs() }
        val videoFile = File(workDir, "video.m4s")
        val audioFile = File(workDir, "audio.m4s")
        val outputDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(applicationContext.filesDir, "downloads")
        if (!outputDir.exists() && !outputDir.mkdirs()) return failure("Cannot create download folder")
        val outputFile = File(outputDir, fileName)
        val stagingFile = File(outputDir, "$fileName.partial")

        return try {
            require(isTrustedMediaUrl(videoUrl) && isTrustedMediaUrl(audioUrl)) {
                "The platform returned an untrusted media host"
            }
            require(referer.isBlank() || isTrustedReferer(referer)) { "Invalid video page reference" }
            setForeground(foregroundInfo(0, true, "Preparing video"))
            val videoBytes = download(videoUrl, videoFile, referer, 0L)
            val audioBytes = download(audioUrl, audioFile, referer, videoBytes)
            val totalBytes = videoBytes + audioBytes
            setProgress(workDataOf(
                KEY_BYTES_DOWNLOADED to totalBytes,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_STAGE to "merging"
            ))
            setForeground(foregroundInfo(95, false, "Merging audio and video"))
            mux(videoFile, audioFile, stagingFile)
            if (outputFile.exists() && !outputFile.delete()) {
                throw IOException("Cannot replace an existing file with the same name")
            }
            if (!stagingFile.renameTo(outputFile)) {
                stagingFile.copyTo(outputFile, overwrite = true)
                stagingFile.delete()
            }
            setProgress(workDataOf(
                KEY_BYTES_DOWNLOADED to totalBytes,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_STAGE to "completed"
            ))
            Result.success(workDataOf(
                KEY_OUTPUT_PATH to outputFile.absolutePath,
                KEY_BYTES_DOWNLOADED to totalBytes,
                KEY_TOTAL_BYTES to totalBytes
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < 2 && (error is IOException || error.cause is IOException)) {
                Result.retry()
            } else {
                failure(error.message?.take(220) ?: "Download or merge failed")
            }
        } finally {
            workDir.deleteRecursively()
            stagingFile.delete()
        }
    }

    private suspend fun download(url: String, destination: File, referer: String, previousBytes: Long): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (referer.isNotBlank()) header("Referer", referer) }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Media server returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Media server returned an empty stream")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_STREAM_BYTES) throw IOException("Media stream exceeds the 4 GB safety limit")
            var copied = 0L
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        if (isStopped) throw CancellationException("Download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_STREAM_BYTES) throw IOException("Media stream exceeds the 4 GB safety limit")
                        output.write(buffer, 0, count)
                        if (copied - lastUpdate >= PROGRESS_INTERVAL_BYTES) {
                            lastUpdate = copied
                            setProgress(workDataOf(
                                KEY_BYTES_DOWNLOADED to previousBytes + copied,
                                KEY_TOTAL_BYTES to if (declaredLength > 0) previousBytes + declaredLength else -1L,
                                KEY_STAGE to "downloading"
                            ))
                            val percent = if (declaredLength > 0) {
                                ((previousBytes + copied) * 90L / (previousBytes + declaredLength).coerceAtLeast(1L)).toInt().coerceIn(1, 90)
                            } else 0
                            setForeground(foregroundInfo(percent, percent == 0, "Downloading media"))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (declaredLength > 0 && copied != declaredLength) {
                throw IOException("Media stream ended before all bytes arrived")
            }
            return copied
        }
    }

    private fun mux(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrackIndex = findTrack(videoExtractor, "video/")
                ?: throw IOException("The video stream has no supported video track")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
                ?: throw IOException("The audio stream has no supported audio track")
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = muxer.addTrack(audioFormat)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                    muxer.setOrientationHint(rotation)
                }
            }
            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)
            muxer.start()

            val videoBuffer = ByteBuffer.allocateDirect(bufferSize(videoFormat))
            val audioBuffer = ByteBuffer.allocateDirect(bufferSize(audioFormat))
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaMuxer.BufferInfo()
            var videoDone = false
            var audioDone = false

            while (!videoDone || !audioDone) {
                if (isStopped) throw CancellationException("Merge cancelled")
                val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                if (videoTime == Long.MAX_VALUE && audioTime == Long.MAX_VALUE) break
                if (videoTime <= audioTime) {
                    videoBuffer.clear()
                    val size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (size < 0) {
                        videoDone = true
                    } else {
                        videoInfo.set(0, size, videoExtractor.sampleTime, videoExtractor.sampleFlags)
                        muxer.writeSampleData(outputVideoTrack, videoBuffer, videoInfo)
                        videoExtractor.advance()
                    }
                } else {
                    audioBuffer.clear()
                    val size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (size < 0) {
                        audioDone = true
                    } else {
                        audioInfo.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                        muxer.writeSampleData(outputAudioTrack, audioBuffer, audioInfo)
                        audioExtractor.advance()
                    }
                }
            }
            muxer.stop()
        } catch (error: Exception) {
            outputFile.delete()
            if (error is IOException || error is CancellationException) throw error
            throw IOException("This device cannot merge the returned audio/video codecs", error)
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            throw IOException("The merged video file is empty")
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun bufferSize(format: MediaFormat): Int {
        val requested = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 1024 * 1024
        return requested.coerceIn(1024 * 1024, MAX_SAMPLE_BUFFER_BYTES)
    }

    private fun foregroundInfo(progress: Int, indeterminate: Boolean, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VideoFetch Pro")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    private fun safeFileName(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._ -") it else '_' }
            .joinToString("").trim().trim('.').take(120)
        val base = cleaned.ifBlank { "videofetch-video.mp4" }
        return if (base.endsWith(".mp4", true)) base else "$base.mp4"
    }

    private fun isTrustedMediaUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (url.host.endsWith(".bilivideo.com") || url.host.endsWith(".bilivideo.cn"))
    }

    private fun isTrustedReferer(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.host == "www.bilibili.com" &&
            url.username.isEmpty() && url.password.isEmpty()
    }

    companion object {
        const val TAG = "bilibili-dash-merge"
        const val KEY_VIDEO_URL = "dash_video_url"
        const val KEY_AUDIO_URL = "dash_audio_url"
        const val KEY_REFERER = "dash_referer"
        const val KEY_FILE_NAME = "dash_file_name"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "videofetch_downloads"
        private const val NOTIFICATION_ID = 4107
        private const val PROGRESS_INTERVAL_BYTES = 1024L * 1024L
        private const val MAX_STREAM_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
