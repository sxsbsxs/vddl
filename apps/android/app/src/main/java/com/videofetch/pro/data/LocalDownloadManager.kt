package com.videofetch.pro.data

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.videofetch.pro.BuildConfig
import com.videofetch.pro.core.CookieScopePolicy
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LocalDownloadRecord(
    val id: Long,
    val title: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val workId: UUID? = null,
    val localFilePath: String? = null,
    val error: String? = null
)

class LocalDownloadManager(
    context: Context,
    private val sessions: EncryptedLocalSessionStore
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val workManager = WorkManager.getInstance(appContext)

    /** Progressive files use DownloadManager; split DASH streams use a persistent merger worker. */
    fun enqueue(
        providerId: String,
        providerAllowedHosts: Set<String>,
        media: LocalMediaResolution.Available
    ): Long {
        val uri = Uri.parse(media.downloadUrl)
        require(uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()) {
            "Downloads must use HTTPS"
        }
        require(CookieScopePolicy.mayAttachCookie(media.downloadUrl, uri.host!!, providerAllowedHosts)) {
            "The media host is not approved by this provider adapter"
        }
        require(media.mimeType == "video/mp4" || media.mimeType == "video/webm" || media.mimeType == "application/octet-stream") {
            "Unsupported media file type"
        }

        if (media.audioUrl != null) {
            require(providerId == "bilibili") { "Separate-track merge is not enabled for this provider" }
            val audioUri = Uri.parse(media.audioUrl)
            require(audioUri.scheme.equals("https", ignoreCase = true) && !audioUri.host.isNullOrBlank()) {
                "Audio stream must use HTTPS"
            }
            require(CookieScopePolicy.mayAttachCookie(media.audioUrl, audioUri.host!!, providerAllowedHosts)) {
                "The audio host is not approved by this provider adapter"
            }
            require(isTrustedBilibiliCdn(uri.host!!) && isTrustedBilibiliCdn(audioUri.host!!)) {
                "The platform returned an untrusted media host"
            }
            val workId = UUID.randomUUID()
            val request = OneTimeWorkRequestBuilder<BilibiliDashMergeWorker>()
                .setId(workId)
                .setInputData(
                    Data.Builder()
                        .putString(BilibiliDashMergeWorker.KEY_VIDEO_URL, media.downloadUrl)
                        .putString(BilibiliDashMergeWorker.KEY_AUDIO_URL, media.audioUrl)
                        .putString(BilibiliDashMergeWorker.KEY_REFERER, media.referer)
                        .putString(BilibiliDashMergeWorker.KEY_FILE_NAME, safeFileName(media.fileName))
                        .build()
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(BilibiliDashMergeWorker.TAG)
                .addTag("title:${safeFileName(media.fileName)}")
                .build()
            workManager.enqueue(request)
            return taskIdentifier(workId)
        }

        val request = DownloadManager.Request(uri)
            .setTitle(safeFileName(media.fileName))
            .setDescription("VideoFetch Pro - " + media.qualityLabel)
            .setMimeType(media.mimeType)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                safeFileName(media.fileName)
            )
            .addRequestHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
            )

        media.referer?.let { referer ->
            val refererUri = Uri.parse(referer)
            require(refererUri.scheme == "https" && !refererUri.host.isNullOrBlank() && refererUri.userInfo == null) {
                "Invalid provider referer"
            }
            request.addRequestHeader("Referer", referer)
        }
        sessions.cookieHeaderFor(providerId, media.downloadUrl, providerAllowedHosts)?.let {
            request.addRequestHeader("Cookie", it)
        }
        return manager.enqueue(request)
    }

    fun list(): List<LocalDownloadRecord> {
        val cursor = manager.query(DownloadManager.Query())
        val direct = cursor?.useSafely { result ->
            val items = mutableListOf<LocalDownloadRecord>()
            while (result.moveToNext()) {
                items += LocalDownloadRecord(
                    id = result.getLong(result.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    title = result.getString(result.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Video",
                    status = result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    downloadedBytes = result.getLong(result.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    totalBytes = result.getLong(result.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
            items
        }.orEmpty()

        val merged = runCatching { workManager.getWorkInfosByTag(BilibiliDashMergeWorker.TAG).get() }
            .getOrDefault(emptyList())
            .map { info ->
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadManager.STATUS_PENDING
                    WorkInfo.State.RUNNING -> DownloadManager.STATUS_RUNNING
                    WorkInfo.State.SUCCEEDED -> DownloadManager.STATUS_SUCCESSFUL
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DownloadManager.STATUS_FAILED
                }
                LocalDownloadRecord(
                    id = taskIdentifier(info.id),
                    title = info.outputData.getString(BilibiliDashMergeWorker.KEY_OUTPUT_PATH)
                        ?.let { File(it).name }
                        ?: info.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:")
                        ?: "Bilibili video",
                    status = status,
                    downloadedBytes = info.progress.getLong(BilibiliDashMergeWorker.KEY_BYTES_DOWNLOADED,
                        info.outputData.getLong(BilibiliDashMergeWorker.KEY_BYTES_DOWNLOADED, 0L)),
                    totalBytes = info.progress.getLong(BilibiliDashMergeWorker.KEY_TOTAL_BYTES,
                        info.outputData.getLong(BilibiliDashMergeWorker.KEY_TOTAL_BYTES, 0L)),
                    workId = info.id,
                    localFilePath = info.outputData.getString(BilibiliDashMergeWorker.KEY_OUTPUT_PATH),
                    error = info.outputData.getString(BilibiliDashMergeWorker.KEY_ERROR)
                )
            }
        return direct + merged
    }

    fun uriFor(record: LocalDownloadRecord): Uri? {
        val path = record.localFilePath
        if (path != null) {
            val file = File(path)
            if (!file.isFile) return null
            return FileProvider.getUriForFile(
                appContext,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
        }
        return manager.getUriForDownloadedFile(record.id)
    }

    fun remove(record: LocalDownloadRecord): Boolean {
        if (record.workId != null) {
            workManager.cancelWorkById(record.workId)
            record.localFilePath?.let { File(it).delete() }
            return true
        }
        return manager.remove(record.id) > 0
    }

    private fun taskIdentifier(id: UUID): Long = -(abs(id.hashCode().toLong()) + 1L)

    private fun isTrustedBilibiliCdn(host: String): Boolean =
        host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn")

    private fun safeFileName(value: String): String {
        val cleaned = value.map { char -> if (char.isLetterOrDigit() || char in "._ -") char else '_' }
            .joinToString("").trim().trim('.').take(120)
        val base = cleaned.ifBlank { "videofetch-video.mp4" }
        return if (base.endsWith(".mp4", true)) base else "$base.mp4"
    }

    private inline fun <T> Cursor.useSafely(block: (Cursor) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
