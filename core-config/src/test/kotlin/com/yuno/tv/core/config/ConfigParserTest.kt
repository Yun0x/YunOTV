package com.yuno.tv.core.config

import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConfigParserTest {
    private val low = SourceOrigin("low", "Low", "https://example.test/a.json", 10)
    private val high = SourceOrigin("high", "High", "https://example.test/b.txt", 100)
    @Test fun fingerprintIgnoresHostCaseSlashAndDuplicateQueryPairs() {
        assertEquals(SourceFingerprint.create("1", " HTTPS://EXAMPLE.test/api/?b=2&a=1&a=1 "),
            SourceFingerprint.create("1", "https://example.test/api?a=1&b=2"))
        assertNotEquals(SourceFingerprint.create("1", "https://example.test/api", "a"),
            SourceFingerprint.create("1", "https://example.test/api", "b"))
        assertNotEquals(SourceFingerprint.create("1", "https://example.test/api"),
            SourceFingerprint.create("3", "https://example.test/api"))
    }
    @Test fun nestedExtIsCanonicalAndPathCaseIsPreserved() {
        assertEquals(SourceFingerprint.normalizedExt("{\"b\":2,\"a\":{\"z\":1,\"y\":0}}"),
            SourceFingerprint.normalizedExt("{\"a\":{\"y\":0,\"z\":1},\"b\":2}"))
        assertNotEquals(SourceFingerprint.normalizedUrl("https://example.test/Api"),
            SourceFingerprint.normalizedUrl("https://example.test/api"))
    }
    @Test fun txtSupportsCrLfPipesBareUrlsAndComments() {
        val body = "\uFEFF# comment\r\n\r\n// comment\r\n这是注释\r\nA,https://example.test/api\r\nB|https://other.test/api\r\nhttps://third.test/api\r\nwrong,ftp://example.test\r\n"
        val parsed = ConfigParser().parse(body, low)
        assertEquals(3, parsed.sites.size)
        assertEquals(1, parsed.warnings.size)
    }
    @Test fun mergeKeepsHigherPriorityAndBothOrigins() {
        val a = ConfigParser().parse("A,https://example.test/api/", low)
        val b = ConfigParser().parse("B|HTTPS://EXAMPLE.TEST/api", high)
        val result = ConfigMerger().merge(listOf(a, b))
        assertEquals(1, result.sites.size)
        assertEquals("B", result.sites.single().name)
        assertEquals("high", result.sites.single().origin.sourceId)
        assertEquals(setOf("low", "high"), result.sites.single().origins.map { it.sourceId }.toSet())
    }
    @Test fun unsupportedSpiderIsRepresentedAndNeverExecuted() {
        val config = ConfigParser().parse("""{"sites":[{"key":"spider","name":"Custom","type":3,"api":"csp_Custom","ext":{}}]}""", low)
        assertFalse(config.sites.single().supported)
        assertEquals(ErrorCode.UNSUPPORTED, config.warnings.single().code)
    }
    @Test fun m3uKeepsGroupLogoAndChannel() {
        val config = ConfigParser().parse("""#EXTM3U url-tvg="https://example.test/epg.xml"
            |#EXTINF:-1 tvg-logo="https://example.test/logo.png" group-title="News",Station
            |https://example.test/channel.m3u8
        """.trimMargin(), high)
        val channel = config.lives.single()
        assertEquals("News", channel.group)
        assertEquals("Station", channel.name)
        assertEquals("https://example.test/epg.xml", channel.epg)
    }
    @Test fun invalidJsonReturnsDomainError() {
        try { ConfigParser().parse("{broken", low); fail("must reject malformed JSON") }
        catch (error: SourceException) { assertEquals(ErrorCode.CONFIG_FORMAT, error.code) }
    }
    @Test fun tvBoxBooleanFlagsAndNestedLiveGroups() {
        val body = """{"sites":[{"key":"a","name":"A","type":1,"api":"https://example.test/api","searchable":0,"filterable":true}],"lives":[{"name":"News","channels":[{"name":"Station","urls":["https://example.test/a.m3u8"]}]}]}"""
        val result = ConfigParser().parse(body, low)
        assertFalse(result.sites.single().searchable)
        assertTrue(result.sites.single().filterable)
        assertEquals("News", result.lives.single().group)
    }
    @Test fun hlsPlaylistIsAChannelRatherThanAListOfVideoSegments() {
        val origin = high.copy(originUrl = "https://example.test/channel.m3u8")
        val config = ConfigParser().parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n", origin)
        assertEquals("https://example.test/channel.m3u8", config.lives.single().url)
    }
    @Test fun excessiveJsonNestingIsRejectedBeforeRecursiveParsing() {
        try { ConfigParser().parse("{\"sites\":" + "[".repeat(100) + "]".repeat(100) + "}", high); fail("must reject deep nesting") }
        catch (error: SourceException) { assertEquals(ErrorCode.CONFIG_FORMAT, error.code) }
    }
    @Test fun meaningfulQuerySpacesRemainPartOfTheFingerprint() {
        assertNotEquals(SourceFingerprint.create("1", "https://example.test/api?q=two%20words"),
            SourceFingerprint.create("1", "https://example.test/api?q=twowords"))
    }
}
