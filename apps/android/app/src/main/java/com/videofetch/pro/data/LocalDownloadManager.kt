package com.videofetch.pro.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.videofetch.pro.core.CookieScopePolicy
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

data class LocalDownloadRecord(
    val id: String, val title: String, val status: Int, val downloadedBytes: Long, val totalBytes: Long,
    val progressPercent: Int? = null, val errorMessage: String? = null
)

class LocalDownloadManager(context: Context, private val sessions: EncryptedLocalSessionStore) {
    private val appContext = context.applicationContext
    private val androidManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(providerId: String, providerAllowedHosts: Set<String>, media: LocalMediaResolution.Available): String {
        val videoUrls = media.videoSegmentUrls.ifEmpty { listOf(media.downloadUrl) }
        require(videoUrls.size in 1..MAX_VIDEO_SEGMENTS) { "Invalid number of video segments" }
        (videoUrls + listOfNotNull(media.audioDownloadUrl)).forEach { url ->
            val uri = Uri.parse(url)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Downloads must use HTTPS" }
            require(CookieScopePolicy.mayAttachCookie(url, uri.host!!, providerAllowedHosts)) {
                "The media host is not approved by this provider adapter"
            }
        }
        require(media.mimeType == "video/mp4") { "Only verified MP4 streams can be exported" }
        validateReferer(media.referer)
        if (media.audioDownloadUrl == null && videoUrls.size == 1) {
            return "dm:${enqueueDirect(providerId, providerAllowedHosts, media)}"
        }

        val jobId = UUID.randomUUID().toString()
        val jobsDir = File(appContext.filesDir, JOBS_DIRECTORY).apply { mkdirs() }
        val specFile = File(jobsDir, "$jobId.json")
        specFile.writeText(JSONObject()
            .put("title", media.fileName)
            .put("videoUrls", JSONArray(videoUrls))
            .put("audioUrl", media.audioDownloadUrl ?: JSONObject.NULL)
            .put("referer", media.referer ?: JSONObject.NULL)
            .toString())
        val request = OneTimeWorkRequestBuilder<MediaMergeWorker>()
            .setId(jobId)
            .setInputData(workDataOf(MediaMergeWorker.KEY_JOB_ID to jobId.toString(), MediaMergeWorker.KEY_TITLE to media.fileName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(MERGE_TAG)
            .addTag("job:$jobId")
            .addTag("title:${safeFileName(media.fileName)}")
            .build()
        return try {
            workManager.enqueue(request)
            "merge:$jobId"
        } catch (error: Throwable) {
            specFile.delete()
            throw error
        }
    }

    private fun enqueueDirect(providerId: String, providerAllowedHosts: Set<String>, media: LocalMediaResolution.Available): Long {
        val displayName = safeFileName(media.fileName)
        val uniqueName = uniqueDownloadName(displayName)
        val request = DownloadManager.Request(Uri.parse(media.downloadUrl))
            .setTitle(displayName)
            .setDescription("VideoFetch Pro - " + media.qualityLabel)
            .setMimeType(media.mimeType)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_MOVIES, "VideoFetch Pro/" + uniqueName)
            .addRequestHeader("User-Agent", USER_AGENT)
        media.referer?.let { request.addRequestHeader("Referer", it) }
        sessions.cookieHeaderFor(providerId, media.downloadUrl, providerAllowedHosts)?.let { request.addRequestHeader("Cookie", it) }
        return androidManager.enqueue(request)
    }

    fun list(): List<LocalDownloadRecord> {
        val records = mutableListOf<LocalDownloadRecord>()
        androidManager.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                records += LocalDownloadRecord(
                    "dm:$id",
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Video",
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
        }
        workManager.getWorkInfosByTag(MERGE_TAG).get().forEach { info ->
            if (info.state == WorkInfo.State.CANCELLED) return@forEach
            val status = when (info.state) {
                WorkInfo.State.SUCCEEDED -> DownloadManager.STATUS_SUCCESSFUL
                WorkInfo.State.RUNNING -> DownloadManager.STATUS_RUNNING
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadManager.STATUS_PENDING
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DownloadManager.STATUS_FAILED
            }
            records += LocalDownloadRecord(
                "merge:${info.id}",
                info.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:") ?: "Video",
                status, 0L, 0L,
                info.progress.getInt(MediaMergeWorker.KEY_PROGRESS, 0)
                    .takeIf { status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING },
                info.outputData.getString(MediaMergeWorker.KEY_ERROR)
            )
        }
        return records
    }

    fun uriFor(id: String): Uri? {
        if (id.startsWith("dm:")) return id.removePrefix("dm:").toLongOrNull()?.let(androidManager::getUriForDownloadedFile)
        if (!id.startsWith("merge:")) return null
        val uuid = runCatching { UUID.fromString(id.removePrefix("merge:")) }.getOrNull() ?: return null
        val info = runCatching { workManager.getWorkInfoById(uuid).get() }.getOrNull() ?: return null
        val path = info.outputData.getString(MediaMergeWorker.KEY_OUTPUT_PATH) ?: return null
        val file = File(path).takeIf { it.isFile && it.length() > 0L } ?: return null
        return runCatching { FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file) }.getOrNull()
    }

    fun remove(id: String): Boolean {
        if (id.startsWith("dm:")) {
            val downloadId = id.removePrefix("dm:").toLongOrNull() ?: return false
            return androidManager.remove(downloadId) > 0
        }
        if (!id.startsWith("merge:")) return false
        val uuid = runCatching { UUID.fromString(id.removePrefix("merge:")) }.getOrNull() ?: return false
        val info = runCatching { workManager.getWorkInfoById(uuid).get() }.getOrNull()
        workManager.cancelWorkById(uuid)
        val output = info?.outputData?.getString(MediaMergeWorker.KEY_OUTPUT_PATH)?.let(::File)
        val jobId = info?.tags?.firstOrNull { it.startsWith("job:") }?.removePrefix("job:")
        val deletedOutput = output?.let { !it.exists() || it.delete() } ?: true
        val deletedSpec = jobId?.let { File(appContext.filesDir, "$JOBS_DIRECTORY/$it.json").delete() } ?: true
        return info != null && (deletedOutput || deletedSpec)
    }

    fun retry(id: String): String? {
        if (!id.startsWith("merge:")) return null
        val oldId = runCatching { UUID.fromString(id.removePrefix("merge:")) }.getOrNull() ?: return null
        val oldInfo = runCatching { workManager.getWorkInfoById(oldId).get() }.getOrNull() ?: return null
        if (oldInfo.state != WorkInfo.State.FAILED) return null
        val oldSpec = File(appContext.filesDir, "$JOBS_DIRECTORY/$oldId.json")
        if (!oldSpec.isFile) return null
        val newId = UUID.randomUUID()
        val newSpec = File(oldSpec.parentFile, "$newId.json")
        return runCatching {
            oldSpec.copyTo(newSpec, overwrite = false)
            val title = readJobTitle(newId.toString())
            val request = OneTimeWorkRequestBuilder<MediaMergeWorker>()
                .setId(newId)
                .setInputData(workDataOf(MediaMergeWorker.KEY_JOB_ID to newId.toString(), MediaMergeWorker.KEY_TITLE to title))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(MERGE_TAG)
                .addTag("job:$newId")
                .addTag("title:${safeFileName(title)}")
                .build()
            workManager.enqueue(request)
            "merge:$newId"
        }.getOrElse {
            newSpec.delete()
            null
        }
    }

    private fun readJobTitle(jobId: String): String =
        runCatching {
            JSONObject(File(appContext.filesDir, "$JOBS_DIRECTORY/$jobId.json").readText())
                .optString("title")
                .takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull() ?: "Video"

    private fun validateReferer(value: String?) {
        if (value == null) return
        val uri = Uri.parse(value)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid provider referer" }
    }

    private fun uniqueDownloadName(value: String): String {
        val name = safeFileName(value)
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val stem = if (extension == null) name else name.dropLast(extension.length + 1)
        val suffix = UUID.randomUUID().toString().take(8)
        return if (extension == null) "$stem-$suffix" else "$stem-$suffix.$extension"
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "videofetch-video.mp4"
    }

    private companion object {
        const val MERGE_TAG = "videofetch-merged-download"
        const val JOBS_DIRECTORY = "videofetch-download-jobs"
        const val MAX_VIDEO_SEGMENTS = 80
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
