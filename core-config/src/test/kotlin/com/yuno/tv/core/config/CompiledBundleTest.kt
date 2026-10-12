package com.yuno.tv.core.config

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompiledBundleTest {
    private val origin = SourceOrigin("default-bundle", "Original", "https://example.test/config/main.json", 100)
    private fun body(config: UnifiedConfig) = JsonObject().apply {
        addProperty("format", "yunotv-compiled-bundle"); addProperty("version", 1)
        add("config", Gson().toJsonTree(config))
        add("statistics", Gson().toJsonTree(BundleStatistics(deduplicatedSiteCount = config.sites.size)))
    }.toString()
    @Test fun offlineResolutionNeverLoadsChildConfig() = runBlocking {
        val config = TvBoxConfigParser().parse("""{"spider":"spider.jar","sites":[{"name":"JS","type":3,"api":"./drpy.js?v=1","ext":"./rule.js"},{"name":"Own Jar","type":3,"api":"csp_Example","jar":"../custom.jar"}]}""", origin)
        val resolved = ConfigResolver { _, _ -> error("Network is blocked") }.resolve(body(config), origin)
        assertEquals(2, resolved.config.sites.size)
        assertEquals("https://example.test/custom.jar", resolved.config.sites.last().metadata.spider)
        assertEquals(CapabilityKind.DRPY_JS, resolved.config.sites.first().capability.kind)
        assertEquals("https://example.test/config/rule.js", resolved.config.sites.first().ext)
    }
    @Test(expected = SourceException::class) fun tamperedSiteIdentityRejected() {
        val site = UnifiedSite("bad", "x", "X", 1, "https://example.test/api", origin = origin)
        CompiledBundleParser().parse(body(UnifiedConfig(sites = listOf(site))))
    }
    @Test fun relativePlaylistUsesOriginalRemoteBase() {
        val result = M3uConfigParser().parse("#EXTM3U\n#EXTINF:-1 tvg-logo=\"./logo.png\",Original\n../streams/test.m3u8", origin)
        assertEquals("https://example.test/streams/test.m3u8", result.lives.single().url)
        assertEquals("https://example.test/config/logo.png", result.lives.single().logo)
    }
}
