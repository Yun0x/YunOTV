package com.yuno.tv.core.config

import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YunoSourceBundleParserTest {
    @Test fun defaultsDisabledUnknownAndChinese() {
        val bundle = YunoSourceBundleParser().parse("""{"format":"yunotv-source-bundle","unknown":true,"sources":[{"name":"中文","url":"https://example.test/a"},{"url":"https://example.test/b","enabled":false}]}""")
        assertEquals(12, bundle.mergePolicy.timeoutSeconds); assertEquals(4, bundle.mergePolicy.maxDepth)
        assertEquals("中文", bundle.sources.first().name); assertFalse(bundle.sources.last().enabled)
    }
    @Test fun emptyBundleIsValid() { assertTrue(YunoSourceBundleParser().parse("""{"format":"yunotv-source-bundle"}""").sources.isEmpty()) }
    @Test(expected = SourceException::class) fun badJsonIsFriendly() { YunoSourceBundleParser().parse("{") }
    @Test(expected = SourceException::class) fun nonHttpEntryIsRejected() { YunoSourceBundleParser().parse("""{"format":"yunotv-source-bundle","sources":[{"url":"file:///etc/passwd"}]}""") }
}
class RelativeUrlResolverTest {
    @Test fun parentChildAbsoluteAndSignedQuery() {
        assertEquals("https://example.test/js/a.js", RelativeUrlResolver.resolve("https://example.test/config/tv.json", "../js/a.js"))
        assertEquals("https://example.test/config/a.jar?sign=AbC&token=x", RelativeUrlResolver.resolve("https://example.test/config/tv.json", "./a.jar?sign=AbC&token=x"))
        assertEquals("https://other.test/a", RelativeUrlResolver.resolve("https://example.test/tv.json", "https://other.test/a"))
        assertEquals("csp_Custom", RelativeUrlResolver.resolve("https://example.test/a", "csp_Custom"))
    }
}
class UrlNormalizerTest {
    @Test fun meaningfulTokenValuesAndCaseArePreserved() {
        assertNotEquals(SourceFingerprint.normalizedUrl("https://example.test/?token=A"), SourceFingerprint.normalizedUrl("https://example.test/?token=a"))
        assertEquals(SourceFingerprint.normalizedUrl(" HTTP://EXAMPLE.test:80/a/?b=2&a=1&a=1 "), SourceFingerprint.normalizedUrl("http://example.test/a?a=1&b=2"))
    }
}
class SourceFingerprintTest {
    @Test fun stableLengthExtOrderAndProviderType() {
        assertEquals(64, SourceFingerprint.create("1", "https://example.test/a").length)
        assertEquals(SourceFingerprint.create("1", "https://example.test/a", "{\"b\":2,\"a\":1}"), SourceFingerprint.create("1", "https://example.test/a", "{\"a\":1,\"b\":2}"))
        assertNotEquals(SourceFingerprint.create("1", "https://example.test/a"), SourceFingerprint.create("3", "https://example.test/a"))
    }
}
class ConfigDeduplicatorTest {
    @Test fun bundleProvenanceSurvivesSharedParentId() {
        val a = SourceOrigin("bundle", "A", "https://example.test/a.json", 100)
        val b = a.copy(sourceName = "B", originUrl = "https://example.test/b.json", priority = 10)
        val result = ConfigMerger().merge(listOf(TxtConfigParser().parse("A,https://example.test/api", a), TxtConfigParser().parse("B,https://example.test/api/", b)))
        assertEquals("A", result.sites.single().name); assertEquals(2, result.sites.single().origins.size)
    }
}
class CapabilityDetectorTest {
    private val origin = SourceOrigin("x", "X", "https://example.test/config.json", 100)
    @Test fun cmsJarJsXmlDirectAndUnknown() {
        val values = listOf(1 to "https://example.test/api", 3 to "csp_Custom", 3 to "./js/drpy.js", 0 to "https://example.test/xml", 1 to "https://example.test/video.mp4", 9 to "unknown")
        val kinds = values.map { (type,api) -> CapabilityDetector().detect(UnifiedSite("x", "x", "X", type, api, origin = origin)).kind }
        assertEquals(listOf(CapabilityKind.CMS_API, CapabilityKind.SPIDER_JAR, CapabilityKind.DRPY_JS, CapabilityKind.TVBOX_TYPE0, CapabilityKind.DIRECT_MEDIA, CapabilityKind.UNKNOWN), kinds)
    }
}
class TvBoxConfigParserTest {
    @Test fun relativeSpiderExtAndUnsupportedNeverExecute() {
        val parsed = TvBoxConfigParser().parse("""{"spider":"../spider.jar","sites":[{"key":"中文","type":3,"api":"./js/drpy.js","ext":"../js/ext.js"}],"lives":[],"parses":[]}""", SourceOrigin("x","X","https://example.test/config/tv.json",100))
        assertEquals("https://example.test/spider.jar", parsed.spider)
        assertEquals("https://example.test/js/ext.js", parsed.sites.single().ext)
        assertTrue(parsed.sites.single().supported) // Selectable runtime candidate, never executed by parsing.
        assertEquals(CapabilityStatus.PARTIAL, parsed.sites.single().capability.status)
    }
}
class ConfigResolverTest {
    @Test fun cycleDuplicateDisabledDepthAndErrorIsolation() = runBlocking {
        val a = """{"format":"yunotv-source-bundle","sources":[{"name":"good","url":"https://example.test/good","priority":100},{"url":"https://example.test/good","priority":90},{"url":"https://example.test/404"},{"url":"https://example.test/slow"},{"url":"https://example.test/disabled","enabled":false},{"url":"https://example.test/cycle"}]}"""
        val called = mutableListOf<String>()
        val result = ConfigResolver { origin, _ ->
            called += origin.originUrl
            when {
                origin.originUrl.endsWith("good") -> ConfigCache("good", """{"sites":[{"name":"Good","api":"https://example.test/api","type":1}]}""")
                origin.originUrl.endsWith("404") -> throw SourceException(ErrorCode.DOWNLOAD)
                origin.originUrl.endsWith("slow") -> throw SourceException(ErrorCode.TIMEOUT)
                else -> ConfigCache("cycle", """{"urls":[{"url":"https://example.test/root"}]}""")
            }
        }.resolve(a, SourceOrigin("root","Root","https://example.test/root",100))
        assertEquals(1, result.config.sites.size); assertEquals(2, result.statistics.downloadFailedConfigCount)
        assertFalse(called.any { it.endsWith("disabled") }); assertTrue(result.config.warnings.isNotEmpty())
    }
    @Test fun maxDepthStopsBeforeNetworkRequest() = runBlocking {
        var calls = 0
        val body = """{"urls":[{"url":"https://example.test/child"}]}"""
        val result = ConfigResolver { _, _ -> calls++; ConfigCache("x", body) }.resolve(body, SourceOrigin("x","X","https://example.test/root",100), policy = MergePolicy(maxDepth = 1))
        assertEquals(1, calls); assertTrue(result.config.warnings.isNotEmpty())
    }
}

class GenericJsonParserTest {
    @Test fun genericSourcesMapUrlToApiWithoutLeakingDtos() {
        val config = GenericJsonParser().parse("""{"sources":[{"id":"direct","name":"合法直链","url":"https://example.test/v.mp4"}],"channels":[]}""", SourceOrigin("x","X","https://example.test/config.json",100))
        assertEquals(CapabilityKind.DIRECT_MEDIA,config.sites.single().capability.kind)
        assertTrue(config.sites.single().supported)
    }
}
