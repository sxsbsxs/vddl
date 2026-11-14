package com.videofetch.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CookieProviderDetectorTest {
    @Test
    fun detectsBilibiliCookieAutomatically() {
        val target = CookieProviderDetector.detect("SESSDATA=abc; bili_jct=xyz")
        assertNotNull(target)
        assertEquals("bilibili", target?.providerId)
        assertEquals("api.bilibili.com", target?.host)
    }

    @Test
    fun detectsNiconicoCookieAutomatically() {
        val target = CookieProviderDetector.detect("user_session=abc; user_session_secure=xyz")
        assertNotNull(target)
        assertEquals("niconico", target?.providerId)
        assertEquals("www.nicovideo.jp", target?.host)
    }

    @Test
    fun acceptsCookieHeaderPrefixAndIgnoresKeyCase() {
        val target = CookieProviderDetector.detect("Cookie: sessdata=abc; other=value")
        assertEquals("bilibili", target?.providerId)
    }

    @Test
    fun rejectsUnknownOrMixedPlatformCookieHeaders() {
        assertNull(CookieProviderDetector.detect("VISITOR_INFO1_LIVE=abc"))
        assertNull(CookieProviderDetector.detect("SESSDATA=abc; user_session=xyz"))
        assertNull(CookieProviderDetector.detect(""))
    }
}
