package com.videofetch.pro.core
import java.net.URI

object SourceUrlValidator {
    fun isPotentiallyValid(rawValue: String): Boolean {
        val value = rawValue.trim()
        if (value.isEmpty() || value.length > 4096) return false
        return runCatching {
            val uri = URI(value)
            val host = uri.host ?: return@runCatching false
            val isIpLiteral = host.contains(":") || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            uri.scheme.equals("https", ignoreCase = true) &&
                host.isNotBlank() && !isIpLiteral &&
                !host.equals("localhost", ignoreCase = true) &&
                !host.endsWith(".local", ignoreCase = true) &&
                uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.fragment == null
        }.getOrDefault(false)
    }
}
