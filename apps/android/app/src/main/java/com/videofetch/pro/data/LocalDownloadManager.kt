package com.videofetch.pro.data

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.videofetch.pro.core.CookieScopePolicy

data class LocalDownloadRecord(
    val id: Long,
    val title: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

class LocalDownloadManager(
    context: Context,
    private val sessions: EncryptedLocalSessionStore
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * Enqueues a direct media URL returned by a reviewed on-device provider.
     * Only HTTPS video files from that provider's declared hosts are accepted.
     * Cookie headers, when present, are attached to this local device request.
     */
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

        sessions.cookieHeaderFor(providerId, media.downloadUrl, providerAllowedHosts)?.let {
            request.addRequestHeader("Cookie", it)
        }
        return manager.enqueue(request)
    }

    fun list(): List<LocalDownloadRecord> {
        val cursor = manager.query(DownloadManager.Query()) ?: return emptyList()
        return cursor.useSafely { result ->
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
        }
    }

    fun uriFor(id: Long): Uri? = manager.getUriForDownloadedFile(id)

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "videofetch-video.mp4"
    }

    private inline fun <T> Cursor.useSafely(block: (Cursor) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
