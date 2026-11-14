package com.videofetch.pro.core
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlValidatorTest {
    @Test
    fun acceptsHttpsUrlWithPublicHostname() {
        assertTrue(SourceUrlValidator.isPotentiallyValid("https://media.example.com/watch?id=123"))
    }

    @Test
    fun rejectsInsecureOrMalformedUrls() {
        listOf(
            "http://media.example.com/video",
            "file:///etc/passwd",
            "https://127.0.0.1/video",
            "https://user:pass@example.com/video",
            "https://example.com:8443/video",
            "not a url"
        ).forEach { url ->
            assertFalse("Expected rejection for $url", SourceUrlValidator.isPotentiallyValid(url))
        }
    }
}
