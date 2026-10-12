package com.yuno.tv.core.runtime

import android.content.res.AssetManager
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/** Packaged resources are immutable. Only absent entries use the remote artifact repository. */
class RemoteResourceResolver(private val directory: File, private val open: (String) -> java.io.InputStream) {
    constructor(assets: AssetManager, directory: File) : this(directory, { assets.open(it) })
    private data class Entry(val type: String, val hash: String, val asset: String, val size: Long)
    private val lock = Mutex()
    private val entries by lazy {
        val bytes = open("yunotv/resource-index.json").use { ArtifactRepository.readBounded(it, 4 * 1024 * 1024) }
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version").asInt == 1)
        val resources = root.getAsJsonObject("resources")
        require(resources.size() <= 4096)
        resources.entrySet().associate { (url, value) ->
            val item = value.asJsonObject
            val type = item.get("type").asString
            val hash = item.get("sha256").asString
            val asset = item.get("asset").asString
            val size = item.get("size").asLong
            require(type in setOf("JS", "JAR", "PLAYLIST") && hash.matches(Regex("[a-f0-9]{64}")))
            require(asset == if (type == "PLAYLIST") "yunotv/live/$hash.playlist" else "yunotv/${type.lowercase()}/$hash.${type.lowercase()}")
            require(size in 1..maximum(type).toLong())
            key(url) to Entry(type, hash, asset, size)
        }
    }
    suspend fun get(url: String, type: String): Pair<ArtifactRepository.Artifact, File>? = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = entries[key(url)] ?: return@withContext null
            require(entry.type == type)
            val folder = directory.resolve(type.lowercase()).apply { mkdirs() }
            val target = folder.resolve(entry.hash)
            val maximum = maximum(type)
            fun validFile() = target.isFile && target.length() == entry.size && target.length() <= maximum &&
                RuntimePolicy.hash(target.readBytes()) == entry.hash
            if (!validFile()) {
                val bytes = open(entry.asset).use { source ->
                    (if (type == "PLAYLIST") java.util.zip.GZIPInputStream(source) else source).use { ArtifactRepository.readBounded(it, maximum) }
                }
                if (bytes.size.toLong() != entry.size || RuntimePolicy.hash(bytes) != entry.hash) throw RuntimeFailure("ASSET_HASH_MISMATCH")
                if (type == "JAR") JarVerifier().validate(bytes)
                val temp = File.createTempFile("asset-", ".tmp", folder)
                try { temp.writeBytes(bytes); check(temp.renameTo(target)) } finally { temp.delete() }
                val capacity = when (type) { "JS" -> 32L * 1024 * 1024; "JAR" -> 96L * 1024 * 1024; else -> 16L * 1024 * 1024 }
                var total = folder.listFiles().orEmpty().sumOf { it.length() }
                folder.listFiles().orEmpty().filter { it != target }.sortedBy { it.lastModified() }.forEach {
                    if (total > capacity) { total -= it.length(); it.delete() }
                }
            }
            val expected = url.substringAfter(";md5;", "").lowercase()
            if (expected.matches(Regex("[a-f0-9]{32}")) && java.security.MessageDigest.getInstance("MD5")
                    .digest(target.readBytes()).joinToString("") { "%02x".format(it) } != expected) throw RuntimeFailure("HASH_MISMATCH")
            ArtifactRepository.Artifact(key(url), entry.hash, "", "", 0) to target
        }
    }
    suspend fun clear() = lock.withLock { withContext(Dispatchers.IO) { directory.deleteRecursively(); Unit } }
    companion object {
        private fun maximum(type: String) = when (type) { "JS" -> RuntimePolicy.JS_BYTES; "JAR" -> RuntimePolicy.JAR_BYTES; else -> 4 * 1024 * 1024 }
        private fun key(url: String) = url.substringBefore(";md5;").trim().toHttpUrlOrNull()?.toString()
            ?: throw RuntimeFailure("INVALID_URL")
    }
}
