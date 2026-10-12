package com.yuno.tv.core.database

import androidx.room.withTransaction
import com.yuno.tv.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest

/** Room stores validators and file indexes. Legacy v1 bodies move lazily without losing user data. */
class SourceStore(private val database: YunoDatabase, private val directory: File) {
    private val sources = database.sources()
    private val caches = database.caches()
    val records = sources.observe().map { it.map(SourceEntity::model) }
    suspend fun all() = sources.all().map(SourceEntity::model)
    suspend fun get(id: String) = sources.get(id)?.model()
    suspend fun save(source: SourceRecord) = sources.upsert(source.entity())
    suspend fun status(id: String, status: String, error: ErrorCode? = null, warnings: Int? = null, success: Long? = null) =
        sources.status(id, status, error?.name, warnings, success)
    suspend fun finishRefresh(id: String, cache: ConfigCache, warnings: Int, success: Long) {
        if (sources.get(id) == null) return
        saveCache(cache)
        sources.status(id, "OK", null, warnings, success)
    }
    suspend fun cache(id: String): ConfigCache? = withContext(Dispatchers.IO) {
        val row = caches.get(id) ?: return@withContext null
        if (row.body.isNotEmpty()) {
            val model = row.model(row.body)
            saveCache(model)
            return@withContext model
        }
        val file = File(directory, row.fileName)
        val limit = if (row.url.startsWith("asset://yunotv/")) 16 * 1024 * 1024 else 4 * 1024 * 1024
        if (row.fileName.isBlank() || !file.isFile || file.length() > limit) return@withContext null
        row.model(file.readText(Charsets.UTF_8))
    }
    suspend fun saveCache(cache: ConfigCache) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(cache.sourceId.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
        val target = File(directory, name); val temporary = File(directory, "$name.tmp")
        temporary.writeText(cache.body, Charsets.UTF_8)
        check(temporary.renameTo(target))
        caches.upsert(ConfigCacheEntity(cache.sourceId, "", cache.etag, cache.lastModified, cache.hash,
            cache.lastSuccessTime, name, cache.url, cache.contentType, cache.lastAttemptTime, cache.error?.name, cache.latencyMs))
    }
    suspend fun replace(source: SourceRecord, cache: ConfigCache) { saveCache(cache); save(source) }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val rows = caches.all().filter { it.sourceId == id || it.sourceId.startsWith("$id:") }
        database.withTransaction { sources.delete(id); caches.delete(id) }
        rows.forEach { if (it.fileName.isNotBlank()) File(directory, it.fileName).delete() }
    }
    suspend fun clearRemoteCache() = withContext(Dispatchers.IO) {
        val ids = sources.all().filter { !it.local }.map { it.id }.toSet()
        val rows = caches.all().filter { it.sourceId in ids || it.sourceId.contains(':') }
        rows.forEach { caches.delete(it.sourceId); if (it.fileName.isNotBlank()) File(directory, it.fileName).delete() }
    }
}
private fun SourceEntity.model() = SourceRecord(id, name, url, format, local, enabled, priority,
    lastSuccessTime, status, error?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() }, warningCount)
private fun SourceRecord.entity() = SourceEntity(id, name, url, format, local, enabled, priority,
    lastSuccessTime, status, error?.name, warningCount)
private fun ConfigCacheEntity.model(content: String) = ConfigCache(sourceId, content, etag, lastModified, hash, lastSuccessTime,
    url, contentType, lastAttemptTime, error?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() }, latencyMs)
