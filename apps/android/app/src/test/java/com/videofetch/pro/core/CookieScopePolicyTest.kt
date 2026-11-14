package com.videofetch.pro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieScopePolicyTest {
    private val allowed = setOf("www.example.com", "media.example-cdn.com")

    @Test
    fun permitsExactApprovedHttpsHost() {
        assertTrue(
            CookieScopePolicy.mayAttachCookie(
                "https://www.example.com/video.m3u8",
                "www.example.com",
                allowed
            )
        )
    }

    @Test
    fun rejectsSiblingSubdomainAndUnapprovedCdn() {
        assertFalse(
            CookieScopePolicy.mayAttachCookie(
                "https://api.example.com/video",
                "www.example.com",
                allowed
            )
        )
        assertFalse(
            CookieScopePolicy.mayAttachCookie(
                "https://evil.example.net/video",
                "evil.example.net",
                allowed
            )
        )
    }

    @Test
    fun rejectsHttpAndCredentialsInUrl() {
        assertFalse(
            CookieScopePolicy.mayAttachCookie(
                "http://www.example.com/video",
                "www.example.com",
                allowed
            )
        )
        assertFalse(
            CookieScopePolicy.mayAttachCookie(
                "https://user:pass@www.example.com/video",
                "www.example.com",
                allowed
            )
        )
    }
}
