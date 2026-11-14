package com.videofetch.pro.data

interface LocalMediaProvider {
    val providerId: String
    val displayName: String
    val allowedCookieHosts: Set<String>
    fun canHandle(shareUrl: String): Boolean
    suspend fun resolve(shareUrl: String, preferredQuality: VideoQualityPreference, sessions: EncryptedLocalSessionStore): LocalMediaResolution
}

data class LocalMediaFormat(
    val id: String, val label: String, val downloadUrl: String, val fileName: String, val mimeType: String,
    val height: Int? = null, val frameRate: Int? = null, val codec: String? = null,
    val dynamicRange: String? = null, val audioLabel: String? = null, val referer: String? = null,
    val videoSegmentUrls: List<String> = listOf(downloadUrl), val audioUrl: String? = null
) {
    fun toDownloadable() = LocalMediaResolution.Available(
        downloadUrl, fileName, mimeType, label, referer, videoSegmentUrls, audioUrl
    )
    fun details(): String = listOfNotNull(
        height?.takeIf { it > 0 }?.let { "${it}p" },
        frameRate?.takeIf { it > 0 }?.let { "${it}fps" },
        codec, dynamicRange, audioLabel,
        if (audioUrl != null) "Separate audio · merge to MP4" else null,
        if (videoSegmentUrls.size > 1) "${videoSegmentUrls.size} video segments · merge to MP4" else null
    ).joinToString(" · ")
}

data class LocalVideoMetadata(
    val title: String, val coverUrl: String? = null, val uploader: String? = null,
    val durationSeconds: Long? = null, val platformName: String, val formats: List<LocalMediaFormat>,
    val downloadNoteResId: Int? = null, val availableVariantLabels: List<String> = emptyList()
)

sealed interface LocalMediaResolution {
    data class Resolved(val video: LocalVideoMetadata, val providerId: String, val allowedHosts: Set<String>) : LocalMediaResolution
    data class Available(
        val downloadUrl: String, val fileName: String, val mimeType: String, val qualityLabel: String,
        val referer: String? = null, val videoSegmentUrls: List<String> = listOf(downloadUrl),
        val audioDownloadUrl: String? = null
    ) : LocalMediaResolution
    data class Unsupported(val reason: String) : LocalMediaResolution
}

object LocalMediaProviderRegistry {
    private val providers = PlatformMediaProviders.builtIns().toMutableList()
    @Synchronized fun register(provider: LocalMediaProvider) {
        require(providers.none { it.providerId == provider.providerId }) { "Provider already registered: ${provider.providerId}" }
        providers += provider
    }
    @Synchronized fun findFor(shareUrl: String): LocalMediaProvider? =
        providers.firstOrNull { provider -> runCatching { provider.canHandle(shareUrl) }.getOrDefault(false) }
    @Synchronized fun registeredProviderIds(): List<String> = providers.map { it.providerId }
}
