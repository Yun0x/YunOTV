package com.yuno.tv.core.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Health comes from real bounded requests, never executing a site's external runtime. */
class SourceHealthChecker(private val file: File? = null) {
    private val _health = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val health = _health.asStateFlow()
    private val gson = Gson()
    private val diskMutex = Mutex()
    suspend fun restore() = withContext(Dispatchers.IO) {
        val saved: Map<String, SourceHealth> = runCatching {
            if (file?.isFile == true && file.length() < 256_000) gson.fromJson<Map<String, SourceHealth>>(file.readText(), object : TypeToken<Map<String, SourceHealth>>() {}.type) else emptyMap<String, SourceHealth>()
        }.getOrDefault(emptyMap())
        _health.value = saved
    }
    suspend fun <T> measure(site: UnifiedSite, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        try {
            val result = block()
            record(site.id, SourceHealth(SourceHealthStatus.AVAILABLE, System.currentTimeMillis(), System.currentTimeMillis()-start, System.currentTimeMillis()))
            return result
        } catch (error: Exception) {
            error.rethrowCancellation()
            val old = _health.value[site.id]
            record(site.id, SourceHealth(if ((old?.lastSuccess ?: 0) > 0) SourceHealthStatus.DEGRADED else SourceHealthStatus.UNAVAILABLE,
                System.currentTimeMillis(), System.currentTimeMillis()-start, old?.lastSuccess ?: 0, error.friendlyCode()))
            throw error
        }
    }
    private suspend fun record(id: String, value: SourceHealth) {
        _health.update { (it + (id to value)).entries.sortedByDescending { entry -> entry.value.lastChecked }.take(256).associate { entry -> entry.toPair() } }
        diskMutex.withLock {
            val json = withContext(Dispatchers.Default) { gson.toJson(_health.value) }
            withContext(Dispatchers.IO) { runCatching { file?.let { target ->
                target.parentFile.mkdirs(); val temporary = File(target.parentFile, target.name + ".tmp")
                temporary.writeText(json); temporary.renameTo(target)
            } } }
        }
    }
    fun ordered(sites: List<UnifiedSite>): List<UnifiedSite> = sites.sortedWith(compareByDescending<UnifiedSite> { it.origin.priority }
        .thenBy { when (_health.value[it.id]?.status) { SourceHealthStatus.AVAILABLE -> 0; SourceHealthStatus.UNKNOWN, null -> 1; SourceHealthStatus.DEGRADED -> 2; else -> 3 } }
        .thenByDescending { _health.value[it.id]?.lastSuccess ?: 0 }
        .thenBy { _health.value[it.id]?.latencyMs ?: Long.MAX_VALUE })
    suspend fun check(sites: List<UnifiedSite>, provider: (UnifiedSite) -> SourceProvider) = supervisorScope {
        val permits = Semaphore(2)
        sites.filter { it.supported }.take(8).map { site -> async { permits.withPermit {
            networkResult { withTimeout(8_000) { measure(site) { provider(site).getCategories() } } }
        } } }.awaitAll()
    }
}
