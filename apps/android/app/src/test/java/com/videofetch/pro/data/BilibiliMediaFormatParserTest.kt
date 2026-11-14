package com.videofetch.pro.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliMediaFormatParserTest {
    @Test fun buildsOnDemandMpdFromBilibiliSegmentBaseRanges() {
        val data = JSONObject("""{"dash":{"video":[
          {"id":80,"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v/1080.m4s?token=1&x=2","bandwidth":3000000,"mimeType":"video/mp4","codecs":"avc1.640032","width":1920,"height":1080,"frameRate":"60.000","SegmentBase":{"Initialization":"0-821","indexRange":"822-1309"}},
          {"id":120,"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v/2160.m4s","bandwidth":9000000,"mimeType":"video/mp4","codecs":"hev1","width":3840,"height":2160,"SegmentBase":{"Initialization":"0-900","indexRange":"901-1400"}}
        ],"audio":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/a/audio.m4s","bandwidth":192000,"mimeType":"audio/mp4","codecs":"mp4a.40.2","SegmentBase":{"Initialization":"0-836","indexRange":"837-2196"}}]}}""")
        val formats = BilibiliMediaFormatParser.parse(data, 1080, 123L)
        assertEquals(1, formats.size)
        val format = formats.single()
        assertEquals(1080, format.height)
        assertEquals(60, format.frameRate)
        assertTrue(format.dashManifest!!.contains("<MPD"))
        assertTrue(format.dashManifest!!.contains("range=\"0-821\""))
        assertTrue(format.dashManifest!!.contains("range=\"0-836\""))
        assertTrue(format.dashManifest!!.contains("https://upos-sz-mirrorali.bilivideo.com/a/audio.m4s"))
        assertTrue(format.dashManifest!!.contains("&amp;x=2"))
        assertFalse(format.dashManifest!!.contains("2160"))
    }

    @Test fun rejectsUntrustedDashHostsAndMissingSegmentRanges() {
        val untrusted = JSONObject("""{"dash":{"video":[{"baseUrl":"https://evil.example/v.m4s","height":1080}],"audio":[{"baseUrl":"https://cdn.bilivideo.com/a.m4s"}]}}""")
        assertTrue(BilibiliMediaFormatParser.parse(untrusted, null, 12L).isEmpty())
        val missingRanges = JSONObject("""{"dash":{"video":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v.m4s","height":1080}],"audio":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/a.m4s"}]}}""")
        assertTrue(BilibiliMediaFormatParser.parse(missingRanges, null, 12L).isEmpty())
    }

    @Test fun collectsTrustedMultipartProgressiveMp4() {
        val data = JSONObject("""{"format":"mp4","quality":80,"durl":[
          {"url":"https://upos-sz-mirrorali.bilivideo.com/part-1.mp4"},
          {"url":"https://upos-sz-mirrorali.bilivideo.com/part-2.mp4"}]}""")
        val formats = BilibiliMediaFormatParser.parse(data, 1080, 12L)
        assertEquals(2, formats.single().videoSegmentUrls.size)
    }
}
