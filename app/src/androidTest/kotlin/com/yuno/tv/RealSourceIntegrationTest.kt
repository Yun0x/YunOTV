package com.yuno.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.PlaybackResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in remote metadata checks. Playback uses the separately tested original local video only. */
@RunWith(AndroidJUnit4::class)
class RealSourceIntegrationTest {
    @Test fun bundleAndCmsHomeSearchDetailEpisodes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteIntegration") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YunoApplication
        val c = app.container
        withTimeout(150_000) { c.configs.ready.first { it }; c.configs.config.first { it.sites.size > 100 } }
        val candidates = c.configs.config.value.sites.filter { it.supported && it.capability.kind == CapabilityKind.CMS_API && it.api.startsWith("https://") }.take(8)
        var homeCount = 0; var searchCount = 0; var episodes = 0; var resolved = false; var sourceName = ""
        val errors = mutableListOf<String>()
        for (site in candidates) {
            try {
                withTimeout(12_000) {
                    val provider = c.providers.provider(site)
                    val home = provider.getHome()
                    if (home.recent.isEmpty()) return@withTimeout
                    val first = home.recent.first()
                    val found = provider.search(first.name)
                    val detail = provider.getDetail(first.id)
                    if (detail.lines.isEmpty()) return@withTimeout
                    homeCount = home.recent.size; searchCount = found.size
                    episodes = detail.lines.sumOf { it.episodes.size }
                    resolved = runCatching { PlaybackResolver().resolve(detail.lines.first().episodes.first()); true }.getOrDefault(false)
                    sourceName = site.name
                }
                if (homeCount > 0 && searchCount > 0 && episodes > 0) break
            } catch (_: Exception) { errors += site.name }
        }
        val report = linkedMapOf<String,Any>("statistics" to c.configs.statistics.value,
            "capabilities" to CapabilityKind.entries.associate { kind -> kind.name to c.configs.config.value.sites.count { it.capability.kind == kind } },
            "sourceName" to sourceName,"homeCount" to homeCount,"searchCount" to searchCount,"episodeCount" to episodes,
            "playbackUrlResolved" to resolved,"failedSourceNames" to errors)
        app.filesDir.resolve("remote-integration-report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        android.util.Log.i("YunoRemoteIntegration", "metadata home=$homeCount search=$searchCount episodes=$episodes resolved=$resolved")
        assertTrue(homeCount > 0); assertTrue(searchCount > 0); assertTrue(episodes > 0); assertTrue(resolved)
    }
}
