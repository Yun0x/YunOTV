package com.yuno.tv.core.network

import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*
import org.junit.Assert.*
import org.junit.Test
class PlaybackResolverTest {
    @Test fun hlsMp4AndHeadersWithSignedQuery() {
        val hls = PlaybackResolver().resolve(UnifiedEpisode("1","EP","https://example.test/v.m3u8?token=AbC&sign=123", mapOf("Referer" to "https://example.test/", "User-Agent" to "Custom")))
        assertEquals(PlaybackKind.HLS,hls.kind); assertEquals("Custom",hls.userAgent); assertTrue(hls.url.endsWith("?token=AbC&sign=123"))
        assertEquals(PlaybackKind.MP4,PlaybackResolver().resolve(UnifiedEpisode("2","EP","https://example.test/v.mp4")).kind)
    }
    @Test fun unknownContentRemainsHonestAndHeaderInjectionIsRemoved() {
        val result = PlaybackResolver().resolve(UnifiedEpisode("1","EP","https://example.test/stream", mapOf("Referer" to "bad\r\nCookie: hidden")))
        assertEquals(PlaybackKind.UNKNOWN,result.kind); assertTrue(result.headers.isEmpty())
    }
    @Test(expected = SourceException::class) fun webpageNeedsUnsupportedParser() { PlaybackResolver().resolve(UnifiedEpisode("1","EP","https://example.test/v.html")) }
    @Test(expected = SourceException::class) fun localFileCannotBeRemoteMedia() { PlaybackResolver().resolve(UnifiedEpisode("1","EP","file:///etc/passwd")) }
}
