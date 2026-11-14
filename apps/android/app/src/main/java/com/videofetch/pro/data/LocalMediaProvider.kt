package com.videofetch.pro.data

/**
 * Provider adapters run on the device. They must use authorized provider access
 * and may request a cookie only for hosts declared in allowedCookieHosts.
 * Session material must never be sent to the VideoFetch Pro backend.
 */
interface LocalMediaProvider {
    val providerId: String
    val displayName: String
    val allowedCookieHosts: Set<String>

    fun canHandle(shareUrl: String): Boolean

    suspend fun resolve(
        shareUrl: String,
        preferredQuality: VideoQualityPreference,
        sessions: EncryptedLocalSessionStore
    ): LocalMediaResolution
}

data class LocalMediaFormat(
    val id: String,
    val label: String,
    val downloadUrl: String,
    val fileName: String,
    val mimeType: String,
    val height: Int? = null,
    val frameRate: Int? = null,
    val codec: String? = null,
    val dynamicRange: String? = null,
    val audioLabel: String? = null,
    val referer: String? = null
) {
    fun toDownloadable(): LocalMediaResolution.Available = LocalMediaResolution.Available(
        downloadUrl = downloadUrl,
        fileName = fileName,
        mimeType = mimeType,
        qualityLabel = label,
        referer = referer
    )

    fun details(): String = listOfNotNull(
        height?.takeIf { it > 0 }?.let { "${it}p" },
        frameRate?.let { "${it}fps" },
        codec,
        dynamicRange,
        audioLabel
    ).joinToString(" · ")
}

data class LocalVideoMetadata(
    val title: String,
    val coverUrl: String? = null,
    val uploader: String? = null,
    val durationSeconds: Long? = null,
    val platformName: String,
    val formats: List<LocalMediaFormat>,
    val downloadNoteResId: Int? = null
)

sealed interface LocalMediaResolution {
    data class Resolved(
        val video: LocalVideoMetadata,
        val providerId: String,
        val allowedHosts: Set<String>
    ) : LocalMediaResolution

    data class Available(
        val downloadUrl: String,
        val fileName: String,
        val mimeType: String,
        val qualityLabel: String,
        val referer: String? = null
    ) : LocalMediaResolution

    data class Unsupported(val reason: String) : LocalMediaResolution
}

/** Built-in platform adapters are registered explicitly at app startup. */
object LocalMediaProviderRegistry {
    private val providers = PlatformMediaProviders.builtIns().toMutableList()

    @Synchronized
    fun register(provider: LocalMediaProvider) {
        require(providers.none { it.providerId == provider.providerId }) {
            "Provider already registered: ${provider.providerId}"
        }
        providers += provider
    }

    @Synchronized
    fun findFor(shareUrl: String): LocalMediaProvider? =
        providers.firstOrNull { provider -> runCatching { provider.canHandle(shareUrl) }.getOrDefault(false) }

    @Synchronized
    fun registeredProviderIds(): List<String> = providers.map { it.providerId }
}
