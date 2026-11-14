package com.videofetch.pro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMediaProvidersTest {
    private val bili = PlatformMediaProviders.BilibiliProvider()
    private val youtube = PlatformMediaProviders.YouTubeProvider()
    private val nico = PlatformMediaProviders.NiconicoProvider()

    @Test
    fun registryStartsWithAllRequestedPlatforms() {
        assertTrue(LocalMediaProviderRegistry.registeredProviderIds().containsAll(
            listOf("bilibili", "youtube", "niconico")
        ))
    }

    @Test
    fun recognizesBilibiliAndShortLinksWithoutAcceptingLookalikeHosts() {
        assertTrue(bili.canHandle("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertTrue(bili.canHandle("https://b23.tv/abc123"))
        assertFalse(bili.canHandle("https://bilibili.com.example.org/video/BV1xx411c7mD"))
    }

    @Test
    fun recognizesYoutubeLinkVariantsWithoutAcceptingLookalikes() {
        assertTrue(youtube.canHandle("https://youtu.be/abcdefghijk"))
        assertTrue(youtube.canHandle("https://www.youtube.com/shorts/abcdefghijk"))
        assertFalse(youtube.canHandle("https://youtube.com.example.org/watch?v=abcdefghijk"))
    }

    @Test
    fun recognizesNiconicoLinkVariantsWithoutAcceptingLookalikes() {
        assertTrue(nico.canHandle("https://www.nicovideo.jp/watch/sm12345678"))
        assertTrue(nico.canHandle("https://nico.ms/sm12345678"))
        assertFalse(nico.canHandle("https://nicovideo.jp.example.org/watch/sm12345678"))
    }
}
