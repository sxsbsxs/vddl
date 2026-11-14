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
    val progressPercent: Int? = null, val errorMessage: String? = null, val canRetry: Boolean = false
)

class LocalDownloadManager(context: Context, private val sessions: EncryptedLocalSessionStore) {
    private val appContext = context.applicationContext
    private val androidManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(providerId: String, providerAllowedHosts: Set<String>, media: LocalMediaResolution.Available): String {
        val videoUrls = media.videoSegmentUrls.ifEmpty { listOf(media.downloadUrl) }
        require(videoUrls.size in 1..MAX_VIDEO_SEGMENTS) { "Invalid number of video segments" }
        val manifestUrls = media.dashManifest?.let { manifest ->
            Regex("<BaseURL>(.*?)</BaseURL>").findAll(manifest).map {
                it.groupValues[1].replace("&amp;", "&")
            }.toList()
        }.orEmpty()
        if (media.dashManifest != null) require(manifestUrls.size >= 2) { "DASH manifest must include video and audio tracks" }
        val urlsToValidate = if (media.dashManifest != null) manifestUrls else videoUrls + listOfNotNull(media.audioDownloadUrl)
        urlsToValidate.forEach { url ->
            val uri = Uri.parse(url)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Downloads must use safe HTTPS URLs"
            }
            require(CookieScopePolicy.mayAttachCookie(url, uri.host!!, providerAllowedHosts)) {
                "The media host is not approved by this provider adapter"
            }
        }
        require(media.mimeType == "video/mp4") { "Only verified MP4 streams can be exported" }
        validateReferer(media.referer)
        if (media.dashManifest == null && media.audioDownloadUrl == null && videoUrls.size == 1) {
            return "dm:${enqueueDirect(providerId, providerAllowedHosts, media)}"
        }

        val jobId = UUID.randomUUID().toString()
        val jobsDir = File(appContext.filesDir, JOBS_DIRECTORY).apply { mkdirs() }
        val specFile = File(jobsDir, "$jobId.json")
        specFile.writeText(JSONObject()
            .put("title", media.fileName)
            .put("videoUrls", JSONArray(videoUrls))
            .put("audioUrl", media.audioDownloadUrl ?: JSONObject.NULL)
            .put("dashManifest", media.dashManifest ?: JSONObject.NULL)
            .put("referer", media.referer ?: JSONObject.NULL)
            .toString())
        val request = OneTimeWorkRequestBuilder<MediaMergeWorker>()
            .setId(UUID.fromString(jobId))
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
        val downloadId = androidManager.enqueue(request)
        return try {
            writeDirectSpec(downloadId, providerId, providerAllowedHosts, media)
            downloadId
        } catch (error: Throwable) {
            androidManager.remove(downloadId)
            throw error
        }
    }

    private fun writeDirectSpec(
        downloadId: Long,
        providerId: String,
        allowedHosts: Set<String>,
        media: LocalMediaResolution.Available
    ) {
        val directory = File(appContext.filesDir, JOBS_DIRECTORY).apply { mkdirs() }
        val spec = JSONObject()
            .put("providerId", providerId)
            .put("allowedHosts", JSONArray(allowedHosts.toList()))
            .put("downloadUrl", media.downloadUrl)
            .put("fileName", media.fileName)
            .put("mimeType", media.mimeType)
            .put("qualityLabel", media.qualityLabel)
            .put("referer", media.referer ?: JSONObject.NULL)
        val target = directSpecFile(downloadId)
        val temporary = File(directory, "dm-$downloadId.tmp")
        temporary.writeText(spec.toString())
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not update download retry data")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun directSpecFile(downloadId: Long) =
        File(appContext.filesDir, "$JOBS_DIRECTORY/dm-$downloadId.json")

    private fun retryDirect(downloadId: Long): String? {
        val cursor = androidManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
        val failed = cursor.use {
            it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_FAILED
        }
        if (!failed) return null
        val specFile = directSpecFile(downloadId)
        if (!specFile.isFile) return null
        return runCatching {
            val spec = JSONObject(specFile.readText())
            val hostsJson = spec.optJSONArray("allowedHosts") ?: JSONArray()
            val allowedHosts = buildSet {
                for (index in 0 until hostsJson.length()) hostsJson.optString(index)
                    .takeIf { it.isNotBlank() }?.let(::add)
            }
            val providerId = spec.getString("providerId")
            require(allowedHosts.isNotEmpty()) { "Download provider data is missing" }
            val media = LocalMediaResolution.Available(
                downloadUrl = spec.getString("downloadUrl"),
                fileName = spec.optString("fileName", "videofetch-video.mp4"),
                mimeType = spec.optString("mimeType", "video/mp4"),
                qualityLabel = spec.optString("qualityLabel", "Video"),
                referer = spec.optString("referer").takeIf { it.isNotBlank() && it != "null" }
            )
            "dm:${enqueueDirect(providerId, allowedHosts, media)}"
        }.getOrNull()
    }

    fun list(): List<LocalDownloadRecord> {
        val records = mutableListOf<LocalDownloadRecord>()
        androidManager.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val retryFile = directSpecFile(id)
                if (status == DownloadManager.STATUS_SUCCESSFUL) retryFile.delete()
                records += LocalDownloadRecord(
                    "dm:$id",
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Video",
                    status,
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    errorMessage = if (status == DownloadManager.STATUS_FAILED) downloadFailureReason(reason) else null,
                    canRetry = status == DownloadManager.STATUS_FAILED && retryFile.isFile
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
            val jobId = info.tags.firstOrNull { it.startsWith("job:") }?.removePrefix("job:")
            val retryFile = jobId?.let { File(appContext.filesDir, "$JOBS_DIRECTORY/$it.json") }
            records += LocalDownloadRecord(
                "merge:${info.id}",
                info.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:") ?: "Video",
                status, 0L, 0L,
                info.progress.getInt(MediaMergeWorker.KEY_PROGRESS, 0)
                    .takeIf { status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING },
                info.outputData.getString(MediaMergeWorker.KEY_ERROR),
                canRetry = status == DownloadManager.STATUS_FAILED && retryFile?.isFile == true
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
            val removed = androidManager.remove(downloadId) > 0
            val metadataRemoved = directSpecFile(downloadId).delete()
            return removed || metadataRemoved
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
        if (id.startsWith("dm:")) {
            val downloadId = id.removePrefix("dm:").toLongOrNull() ?: return null
            return retryDirect(downloadId)
        }
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

    private fun downloadFailureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "下载中断，当前文件无法续传；可以重试"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "找不到下载存储位置"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "同名文件已存在"
        DownloadManager.ERROR_FILE_ERROR -> "文件写入失败，请检查存储空间"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据异常，请重试"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "服务器重定向次数过多"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器拒绝了下载请求（HTTP 错误）"
        else -> if (reason in 400..599) "服务器返回 HTTP $reason" else "下载失败（错误代码 $reason）"
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
