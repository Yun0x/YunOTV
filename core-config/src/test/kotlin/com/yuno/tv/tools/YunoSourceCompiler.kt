package com.yuno.tv.tools

import com.google.gson.*
import com.yuno.tv.core.config.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Build-only entry point for syncYunoSources. Never shipped in the APK or run by ordinary tests. */
class YunoSourceCompiler {
    @Test fun compile() = runBlocking {
        assumeTrue(System.getProperty("yuno.compiler") == "true")
        val workspace = File(System.getProperty("yuno.workspace"))
        val staging = workspace.resolve("build/yuno-source-staging")
        val root = JsonParser.parseString(staging.resolve("downloaded-configs.json").readText()).asJsonObject
        val documents = root.getAsJsonObject("documents").entrySet().associate { (url, item) ->
            val row = item.asJsonObject
            val body = row.get("body")?.asString.orEmpty()
            SourceFingerprint.normalizedUrl(url) to ConfigCache("compiler", body, url = url,
                contentType = row.get("contentType")?.asString.orEmpty(),
                error = if (row.has("error")) ErrorCode.DOWNLOAD else null)
        }
        val manifest = workspace.resolve("sources.txt.json").readText()
        val result = ConfigResolver(maximumConfigs = 2048, maximumSites = 20_000, maximumPlaylists = 0,
            deduplicateDocuments = true, keepPlaylistReferences = true) { origin, _ ->
            documents[SourceFingerprint.normalizedUrl(origin.originUrl)]
                ?: ConfigCache("compiler", "", error = ErrorCode.DOWNLOAD)
        }.resolve(manifest, SourceOrigin("default-bundle", "Yuno TV Source Bundle", "local://sources.txt.json", 100))
        require(result.config.sites.isNotEmpty()) { "No valid sites; previous assets retained" }
        val compiled = JsonObject().apply {
            addProperty("format", "yunotv-compiled-bundle"); addProperty("version", 1)
            addProperty("generatedAt", java.time.Instant.now().toString())
            addProperty("manifestSha256", SourceFingerprint.sha256(manifest))
            add("config", Gson().toJsonTree(result.config))
            add("statistics", Gson().toJsonTree(result.statistics))
        }
        val body = GsonBuilder().disableHtmlEscaping().create().toJson(compiled)
        println("Validation: bytes=${body.toByteArray().size}, sites=${result.config.sites.size}, lives=${result.config.lives.size}, parses=${result.config.parses.size}")
        staging.resolve("compiled-candidate.json").writeText(body)
        CompiledBundleParser().parse(body)
        staging.resolve("yunotv/yunotv-bundle.json").apply { parentFile.mkdirs(); writeText(body) }
        println("Parsed Sites: ${result.statistics.parsedSiteCount}; Deduplicated Sites: ${result.config.sites.size}; Live Channels: ${result.config.lives.size}")
    }
}
