package com.videofetch.pro.data

/**
 * Provider adapters run on the device. They must only use documented,
 * authorized provider access and may request a cookie only for hosts declared
 * in allowedCookieHosts. Do not send session material to the backend API.
 */
interface LocalMediaProvider {
    val providerId: String
    val displayName: String
    val allowedCookieHosts: Set<String>

    suspend fun resolve(
        shareUrl: String,
        preferredQuality: VideoQualityPreference,
        sessions: EncryptedLocalSessionStore
    ): LocalMediaResolution
}

sealed interface LocalMediaResolution {
    data class Available(
        val downloadUrl: String,
        val fileName: String,
        val mimeType: String,
        val qualityLabel: String
    ) : LocalMediaResolution

    data class Unsupported(val reason: String) : LocalMediaResolution
}
