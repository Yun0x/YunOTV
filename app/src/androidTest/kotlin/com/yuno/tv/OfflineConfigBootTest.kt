package com.yuno.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import com.yuno.tv.core.config.CompiledBundleParser
import com.yuno.tv.core.model.*
import com.yuno.tv.core.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run with the emulator HTTP proxy rejecting GitHub, on a fresh application data directory. */
@RunWith(AndroidJUnit4::class)
class OfflineConfigBootTest {
    @Test fun localBootAndProvidersWithGitHubBlocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as YunoApplication
        withTimeout(30_000) { app.container.configs.ready.first { it } }
        val config = app.container.configs.config.value
        assertTrue(config.sites.isNotEmpty())
        val stored = app.container.configs.allSources().first { it.id == "default-bundle" }
        assertEquals("COMPILED", stored.format)
        assertTrue(stored.local)
        val compiled = CompiledBundleParser().parse(context.assets.open("yunotv/yunotv-bundle.json").bufferedReader().use { it.readText() })
        assertEquals(compiled.config.sites.size, config.sites.size)
        // Fail EVERY remote request, not only GitHub: packaged artifact paths must win.
        val blockedHttp = app.container.network.http.newBuilder().addInterceptor { error("Remote artifact download forbidden") }.build()
        val runtime = CompatibilityRuntime(context, blockedHttp, context.filesDir.resolve("offline-qa-runtime"))
        val index = JsonParser.parseString(context.assets.open("yunotv/resource-index.json").bufferedReader().use { it.readText() }).asJsonObject.getAsJsonObject("resources")
        val engine = config.sites.first { site ->
            site.capability.kind == CapabilityKind.DRPY_JS && site.api.contains("drpy", true) && index.has(site.api)
        }
        val script = runtime.scripts.get(engine.api)
        assertEquals(index.getAsJsonObject(engine.api).get("sha256").asString, script.first.hash)
        val originalRule = "var rule={title:'Yuno Original Offline QA',host:'https://example.invalid',url:'/list/fyclass',class_name:'Original',class_url:'original',一级:[],二级:'*',搜索:[]};"
        val js = engine.copy(ext = originalRule)
        app.container.providers.provider(config.sites.first { it.capability.kind == CapabilityKind.CMS_API })
        runtime.provider(js)
        assertTrue(runtime.call(js, "inspect", JsonArray()).contains("initialized"))
        val jarSites = config.sites.filter { site -> site.capability.kind == CapabilityKind.SPIDER_JAR &&
            index.has(site.metadata.spider.substringBefore(";md5;")) && site.api.startsWith("csp_") }
            .sortedBy { if (it.api == "csp_Push") 0 else 1 }.distinctBy { it.metadata.spider + it.api }.take(20)
        var jarLoaded = false
        for (site in jarSites) {
            runtime.provider(site)
            if (runCatching { runtime.call(site, "inspect", JsonArray()).contains("loaded") }.getOrDefault(false)) { jarLoaded = true; break }
        }
        assertTrue("Actual local JAR class must load", jarLoaded)
        val localPlaylist = config.playlists.firstOrNull { index.has(it.url) }
        if (localPlaylist != null) assertTrue(app.container.configs.livePlaylist(localPlaylist.id).isNotEmpty())
        val github = java.net.URL("https://raw.githubusercontent.com/").openConnection() as java.net.HttpURLConnection
        github.connectTimeout = 3000; github.readTimeout = 3000
        try { assertFalse("GitHub must be blocked", github.responseCode in 200..299) } catch (_: java.io.IOException) {} finally { github.disconnect() }
        runtime.clear()
    }
}
