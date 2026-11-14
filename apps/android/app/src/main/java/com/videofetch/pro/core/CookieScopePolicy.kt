package com.videofetch.pro.core

import java.net.URI

object CookieScopePolicy {
    fun mayAttachCookie(
        requestUrl: String,
        storedHost: String,
        adapterAllowedHosts: Set<String>
    ): Boolean {
        val parsed = runCatching { URI(requestUrl) }.getOrNull() ?: return false
        val requestHost = parsed.host?.lowercase() ?: return false
        val expectedHost = storedHost.trim().trimEnd('.').lowercase()
        val allowlist = adapterAllowedHosts.map { it.trim().trimEnd('.').lowercase() }.toSet()

        return parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.userInfo == null &&
            parsed.port in listOf(-1, 443) &&
            requestHost == expectedHost &&
            requestHost in allowlist
    }
}
