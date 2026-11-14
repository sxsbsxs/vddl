package com.videofetch.pro.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliMediaFormatParserTest {
    @Test fun combinesDashTracksAtRequestedQuality() {
        val data = JSONObject("""{"dash":{"video":[
          {"id":80,"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v/1080.m4s","height":1080,"frameRate":"60"},
          {"id":120,"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v/2160.m4s","height":2160,"frameRate":"60"}
        ],"audio":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/a/audio.m4s","bandwidth":192000}]}}""")
        val formats = BilibiliMediaFormatParser.parse(data, 1080)
        assertEquals(1, formats.size)
        assertEquals(1080, formats.single().height)
        assertEquals(60, formats.single().frameRate)
        assertTrue(formats.single().audioUrl!!.contains("audio.m4s"))
    }
    @Test fun rejectsUntrustedDashHosts() {
        val data = JSONObject("""{"dash":{"video":[{"baseUrl":"https://evil.example/v.m4s","height":1080}],"audio":[{"baseUrl":"https://cdn.bilivideo.com/a.m4s"}]}}""")
        assertTrue(BilibiliMediaFormatParser.parse(data, null).isEmpty())
    }
    @Test fun collectsTrustedMultipartMp4() {
        val data = JSONObject("""{"format":"mp4","quality":80,"durl":[
          {"url":"https://upos-sz-mirrorali.bilivideo.com/part-1.mp4"},
          {"url":"https://upos-sz-mirrorali.bilivideo.com/part-2.mp4"}]}""")
        val formats = BilibiliMediaFormatParser.parse(data, 1080)
        assertEquals(2, formats.single().videoSegmentUrls.size)
        assertNull(formats.single().audioUrl)
    }
}
