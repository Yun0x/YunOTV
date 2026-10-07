package com.yuno.tv.core.network

import com.google.gson.Gson
import com.yuno.tv.core.model.UnifiedVod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yuno.tv.core.hexDigest
import java.io.File

/** Bounded private offline snapshots, independent of HTTP revalidation cache. */
class MediaContentCache(private val directory: File?) {
    private val gson = Gson()
    private val writeMutex = Mutex()
    private fun name(key: String) = hexDigest(key)
    suspend fun saveDetail(vod: UnifiedVod) = write("detail:${vod.identity}", withContext(Dispatchers.Default) { gson.toJson(vod) })
    suspend fun detail(source: String, id: String): UnifiedVod? = read("detail:$source|$id")?.let { body ->
        withContext(Dispatchers.Default) { runCatching { gson.fromJson(body, UnifiedVod::class.java) }.getOrNull() }
    }
    suspend fun saveHome(home: HomeContent) = write("home", withContext(Dispatchers.Default) { gson.toJson(home) })
    suspend fun home(): HomeContent? = read("home")?.let { body ->
        withContext(Dispatchers.Default) { runCatching { gson.fromJson(body, HomeContent::class.java) }.getOrNull() }
    }
    private suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { directory?.resolve("media-${name(key)}.json")?.takeIf { it.isFile && it.length() <= 1024 * 1024 }?.readText() }.getOrNull()
    }
    private suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) { writeMutex.withLock {
        if (value.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return@withLock
        runCatching { directory?.let { folder ->
            folder.mkdirs()
            val target = folder.resolve("media-${name(key)}.json"); val tmp = folder.resolve("${target.name}.tmp")
            tmp.writeText(value); tmp.renameTo(target)
            folder.listFiles()?.filter { it.name.startsWith("media-") && it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
        } }; Unit
    } }
}
