package com.yuno.tv.core.config

import com.google.gson.GsonBuilder
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.NetworkClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Explicit opt-in; third-party availability never gates the ordinary unit suite. */
class SourceBundleIntegrationTest {
    @Test fun realRootBundlePipeline() = runBlocking {
        assumeTrue(System.getProperty("yuno.remote.integration") == "1")
        val root = File(System.getProperty("yuno.project.root"))
        val bundle = root.resolve("sources.txt.json").readText()
        val memo = mutableMapOf<String, ConfigCache>()
        val resolver = ConfigResolver { origin, policy ->
            val key = SourceFingerprint.normalizedUrl(origin.originUrl)
            memo[key] ?: withContext(Dispatchers.IO) {
                val connection = URL(origin.originUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = policy.timeoutSeconds * 1000; connection.readTimeout = policy.timeoutSeconds * 1000
                connection.setRequestProperty("User-Agent", "YunoTV/0.2")
                try {
                    if (connection.responseCode !in 200..299) throw com.yuno.tv.core.SourceException(ErrorCode.DOWNLOAD)
                    val bytes = connection.inputStream.use { it.readNBytes(NetworkClient.MAX_CONFIG_BYTES + 1) }
                    if (bytes.size > NetworkClient.MAX_CONFIG_BYTES) throw com.yuno.tv.core.SourceException(ErrorCode.CONFIG_FORMAT)
                    ConfigCache(key, NetworkClient.decodeUtf8(bytes), url = origin.originUrl, contentType = connection.contentType.orEmpty()).also { memo[key] = it }
                } finally { connection.disconnect() }
            }
        }
        val result = resolver.resolve(bundle, SourceOrigin("default-bundle","Yuno Bundle","local://sources.txt.json",100))
        val report = linkedMapOf<String, Any>("statistics" to result.statistics,
            "capabilities" to CapabilityKind.entries.associate { kind -> kind.name to result.config.sites.count { it.capability.kind == kind } },
            "liveChannels" to result.config.lives.size, "diagnostics" to result.diagnostics)
        root.resolve("docs/default-bundle-integration.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        assertEquals(4, result.statistics.bundleSourceCount); assertEquals(4,result.statistics.enabledConfigCount)
        assertTrue(result.config.sites.isNotEmpty()); assertTrue(result.config.sites.any { it.supported })
        assertTrue(result.config.sites.size < result.statistics.parsedSiteCount)
    }
}
