package com.videofetch.pro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.videofetch.pro.core.CookieScopePolicy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalSessionProfile(val providerId: String, val host: String)

class EncryptedLocalSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveCookie(providerId: String, host: String, cookieHeader: String) {
        val safeProvider = normalizeProviderId(providerId)
        val safeHost = normalizeHost(host)
        val safeCookie = normalizeCookieHeader(cookieHeader)
        val plainText = "$safeHost\n$safeCookie".toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(safeProvider.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plainText)
        val iv = cipher.iv
        val packed = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()

        check(preferences.edit().putString(storageKey(safeProvider), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not save the encrypted local session"
        }
    }

    fun hasCookie(providerId: String): Boolean {
        val safeProvider = normalizeProviderId(providerId)
        return preferences.contains(storageKey(safeProvider))
    }

    fun profile(providerId: String): LocalSessionProfile? {
        val safeProvider = normalizeProviderId(providerId)
        val entry = decrypt(safeProvider) ?: return null
        return LocalSessionProfile(safeProvider, entry.first)
    }

    fun clearCookie(providerId: String) {
        val safeProvider = normalizeProviderId(providerId)
        check(preferences.edit().remove(storageKey(safeProvider)).commit()) {
            "Could not remove the local session"
        }
    }

    /**
     * Call only from a provider adapter. Cookies are returned only for HTTPS
     * requests whose hostname exactly matches both the saved host and the
     * adapter's explicit host allow-list. No wildcard or parent-domain match.
     */
    fun cookieHeaderFor(
        providerId: String,
        requestUrl: String,
        adapterAllowedHosts: Set<String>
    ): String? {
        val safeProvider = normalizeProviderId(providerId)
        val entry = decrypt(safeProvider) ?: return null
        return if (CookieScopePolicy.mayAttachCookie(requestUrl, entry.first, adapterAllowedHosts)) {
            entry.second
        } else {
            null
        }
    }

    private fun decrypt(providerId: String): Pair<String, String>? {
        val encoded = preferences.getString(storageKey(providerId), null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.isNotEmpty())
            val ivSize = packed[0].toInt() and 0xff
            require(ivSize in 12..16 && packed.size > 1 + ivSize)

            val iv = packed.copyOfRange(1, 1 + ivSize)
            val encrypted = packed.copyOfRange(1 + ivSize, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(providerId.toByteArray(StandardCharsets.UTF_8))
            val plain = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
            val separator = plain.indexOf('\n')
            require(separator > 0)
            val host = plain.substring(0, separator)
            val cookie = plain.substring(separator + 1)
            host to cookie
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun normalizeProviderId(value: String): String {
        val normalized = value.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_-]{2,48}"))) {
            "Provider ID must use 2–48 letters, numbers, underscores, or hyphens"
        }
        return normalized
    }

    private fun normalizeHost(value: String): String {
        val normalized = value.trim().trimEnd('.').lowercase()
        require(normalized.isNotBlank() && !normalized.contains('/') && !normalized.contains('@'))
        val parsed = URI("https://$normalized/")
        val host = parsed.host ?: throw IllegalArgumentException("Enter a hostname such as www.example.com")
        require(parsed.port == -1 && parsed.userInfo == null && parsed.rawAuthority == normalized)
        require(host == normalized && host.contains('.') && !host.equals("localhost", true))
        require(!host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) && !host.contains(':'))
        return normalized
    }

    private fun normalizeCookieHeader(value: String): String {
        val trimmed = value.trim().removePrefix("Cookie:").trim()
        require(trimmed.isNotBlank() && trimmed.length <= MAX_COOKIE_LENGTH)
        require(trimmed.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Paste one Cookie header line, not a multi-line Set-Cookie response"
        }
        val pairs = trimmed.split(';')
        require(pairs.all { pair ->
            val equalsAt = pair.indexOf('=')
            equalsAt > 0 && pair.substring(0, equalsAt).trim().isNotEmpty()
        }) { "Cookie format should look like name=value; other=value" }
        return trimmed
    }

    private fun storageKey(providerId: String) = "profile_$providerId"

    private companion object {
        const val PREFERENCES = "videofetch_local_session_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "videofetch_local_cookie_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_COOKIE_LENGTH = 16_384
    }
}
