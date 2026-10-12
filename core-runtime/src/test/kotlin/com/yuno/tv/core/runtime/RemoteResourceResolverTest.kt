package com.yuno.tv.core.runtime

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RemoteResourceResolverTest {
    @Test fun compressedPlaylistUsesDecodedHashAndRetainsItsIndexedAssetName() = runBlocking {
        val folder = Files.createTempDirectory("yuno-playlist").toFile()
        try {
            val bytes = "#EXTM3U\n#EXTINF:-1,Original\nhttps://example.test/legal.m3u8".toByteArray()
            val hash = RuntimePolicy.hash(bytes)
            val compressed = java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
            }.toByteArray()
            val index = """{"version":1,"resources":{"https://example.test/live.m3u":{"type":"PLAYLIST","sha256":"$hash","asset":"yunotv/live/$hash.playlist","size":${bytes.size}}}}"""
            val resolver = RemoteResourceResolver(folder) { path ->
                if (path.endsWith("resource-index.json")) index.byteInputStream() else {
                    assertTrue(path.endsWith(".playlist")); compressed.inputStream()
                }
            }
            assertArrayEquals(bytes, resolver.get("https://example.test/live.m3u", "PLAYLIST")!!.second.readBytes())
        } finally { folder.deleteRecursively() }
    }
    @Test fun indexedAssetWinsWithoutAnyHttpAndRepairsCorruptExtractedFile() = runBlocking {
        val folder = Files.createTempDirectory("yuno-assets").toFile()
        try {
            val bytes = "export default {};".toByteArray()
            val hash = RuntimePolicy.hash(bytes)
            val url = "https://raw.githubusercontent.com/example/test/main/a.js"
            val asset = "yunotv/js/$hash.js"
            val index = JsonObject().apply {
                addProperty("version", 1)
                add("resources", JsonObject().apply { add(url, JsonObject().apply {
                    addProperty("type", "JS"); addProperty("sha256", hash); addProperty("asset", asset); addProperty("size", bytes.size)
                }) })
            }.toString().toByteArray()
            val resolver = RemoteResourceResolver(folder.resolve("assets")) { if (it.endsWith("resource-index.json")) index.inputStream() else bytes.inputStream() }
            val http = OkHttpClient.Builder().addInterceptor { error("All HTTP blocked") }.build()
            val repository = ArtifactRepository(folder.resolve("cache"), http, 1024, 4096, local = { resolver.get(it, "JS") })
            val first = repository.get(url)
            assertEquals(hash, first.first.hash)
            first.second.writeText("corrupt")
            assertArrayEquals(bytes, repository.get(url).second.readBytes())
            resolver.clear()
            assertArrayEquals(bytes, repository.get(url).second.readBytes())
            assertNull(resolver.get("https://example.test/absent.js", "JS"))
        } finally { folder.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun assetTraversalRejected() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("yuno-invalid").toFile()
        try {
            val index = """{"version":1,"resources":{"https://example.test/a.js":{"type":"JS","sha256":"${"a".repeat(64)}","asset":"../private","size":1}}}"""
            RemoteResourceResolver(folder) { index.byteInputStream() }.get("https://example.test/a.js", "JS")
        } finally { folder.deleteRecursively() }
    }
}
