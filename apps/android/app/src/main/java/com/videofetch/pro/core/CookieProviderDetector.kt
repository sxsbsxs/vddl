package com.videofetch.pro.core

/**
 * Identifies supported platform Cookie headers from their well-known key names.
 * The returned host is the exact HTTPS API host to which the local adapter may
 * attach the Cookie; raw Cookie contents are never inspected beyond key names.
 */
data class CookieProviderTarget(
    val providerId: String,
    val host: String,
    val displayName: String
)

object CookieProviderDetector {
    private val bilibiliKeys = setOf("sessdata", "bili_jct", "dedeuserid")
    private val niconicoKeys = setOf("user_session", "user_session_secure")

    fun detect(rawCookieHeader: String): CookieProviderTarget? {
        val normalized = rawCookieHeader.trim()
            .removePrefix("Cookie:")
            .trim()
        if (normalized.isBlank() || normalized.length > 16_384) return null

        val names = normalized.split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator).trim().lowercase()
            }
            .toSet()

        val isBilibili = names.any { it in bilibiliKeys }
        val isNiconico = names.any { it in niconicoKeys }

        return when {
            isBilibili && !isNiconico ->
                CookieProviderTarget("bilibili", "api.bilibili.com", "Bilibili")
            isNiconico && !isBilibili ->
                CookieProviderTarget("niconico", "www.nicovideo.jp", "Niconico")
            else -> null
        }
    }
}
