package com.yuno.tv.core.config

import com.google.gson.Gson
import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*

data class CompiledBundle(val config: UnifiedConfig, val statistics: BundleStatistics)

/** The compiler serializes our own models. Remote TVBox documents never reach this boundary. */
class CompiledBundleParser {
    fun parse(body: String): CompiledBundle = try {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = jsonObject(body)
        require(root.string("format") == "yunotv-compiled-bundle" && root.int("version", 0) == 1)
        val config = Gson().fromJson(root.getAsJsonObject("config"), UnifiedConfig::class.java)
        require(config.sites.size <= 20_000 && config.lives.size <= 50_000 && config.parses.size <= 5_000 && config.playlists.size <= 2048)
        require(config.sites.map { it.id }.distinct().size == config.sites.size)
        config.sites.forEach { site ->
            require(site.id == SourceFingerprint.create(site.type.toString(), site.api, site.ext))
            require(site.name.isNotBlank() && site.origins.isNotEmpty() && site.metadata.headers.size <= 64)
        }
        config.lives.forEach { require(it.id.isNotBlank() && it.url.isNotBlank() && it.origins.isNotEmpty()) }
        config.parses.forEach { require(it.id.isNotBlank() && it.url.isNotBlank()) }
        val statistics = Gson().fromJson(root.getAsJsonObject("statistics"), BundleStatistics::class.java)
        require(statistics.deduplicatedSiteCount == config.sites.size)
        // Capabilities are protocol candidates, never evidence that a remote plugin works.
        CompiledBundle(CapabilityDetector().apply(config), statistics)
    } catch (_: Exception) { throw SourceException(ErrorCode.CONFIG_FORMAT) }

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
