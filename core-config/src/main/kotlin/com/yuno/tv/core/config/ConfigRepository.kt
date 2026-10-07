package com.yuno.tv.core.config

import com.yuno.tv.core.*
import com.yuno.tv.core.database.SourceStore
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.NetworkClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConfigRepository(
    private val store: SourceStore, private val network: NetworkClient,
    private val settings: SettingsRepository, private val scope: CoroutineScope, private val logger: AppLogger,
    private val defaultBundle: suspend () -> String = { "" }
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val rebuildMutex = Mutex()
    private val refreshMutex = Mutex()
    private val _config = MutableStateFlow(UnifiedConfig())
    val config: StateFlow<UnifiedConfig> = _config.asStateFlow()
    val sources = store.records.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _diagnostics = MutableStateFlow<List<ConfigDiagnostic>>(emptyList())
    val diagnostics = _diagnostics.asStateFlow()
    private val _statistics = MutableStateFlow(BundleStatistics())
    val statistics = _statistics.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    suspend fun initialize() {
        if (!settings.settings.first().bundleImported) {
            val body = withContext(Dispatchers.IO) { defaultBundle() }
            if (body.isNotBlank()) {
                withContext(Dispatchers.Default) { YunoSourceBundleParser().parse(body) }
                store.replace(SourceRecord("default-bundle", "Yuno TV Source Bundle", "local://sources.txt.json", "BUNDLE", local = true),
                    ConfigCache("default-bundle", body, hash = SourceFingerprint.sha256(body)))
            }
            settings.update { it.copy(bundleImported = true) }
        }
        rebuild(); _ready.value = true
        scope.launch { store.records.map { rows -> rows.map { listOf(it.id, it.enabled, it.priority, it.url, it.format) } }.distinctUntilChanged().drop(1).collect { rebuild() } }
        scope.launch {
            if (settings.settings.first().autoRefresh) refreshAll()
            while (isActive) { delay(6 * 60 * 60 * 1000L); if (settings.settings.first().autoRefresh) refreshAll() }
        }
    }
    suspend fun allSources(): List<SourceRecord> = store.all()
    suspend fun addRemote(name: String, url: String, format: String, priority: Int): String {
        val value = url.trim()
        if (value.toHttpUrlOrNull() == null) throw SourceException(ErrorCode.INVALID_URL)
        val source = SourceRecord(UUID.randomUUID().toString(), name.trim().ifBlank { value.toHttpUrlOrNull()!!.host }, value, format, priority = priority)
        store.save(source); refresh(source.id); return source.id
    }
    suspend fun importLocal(name: String, bytes: ByteArray, format: String = "AUTO") = withContext(Dispatchers.IO) {
        if (bytes.size > NetworkClient.MAX_CONFIG_BYTES) throw SourceException(ErrorCode.CONFIG_FORMAT)
        val body = NetworkClient.decodeUtf8(bytes)
        val detected = withContext(Dispatchers.Default) { ContentTypeDetector().detect(body) }
        withContext(Dispatchers.Default) {
            if (detected == ConfigContentType.YUNO_BUNDLE) YunoSourceBundleParser().parse(body)
            else if (detected != ConfigContentType.MULTI_REPO) ConfigParser().parse(body, SourceOrigin("", name, "local://$name", 100), format)
        }
        val source = SourceRecord(UUID.randomUUID().toString(), name, "local://$name", if (detected == ConfigContentType.YUNO_BUNDLE) "BUNDLE" else format, local = true)
        val now = System.currentTimeMillis()
        store.replace(source.copy(lastSuccessTime = now, status = "OK"), ConfigCache(source.id, body, hash = SourceFingerprint.sha256(body), lastSuccessTime = now))
        if (detected == ConfigContentType.YUNO_BUNDLE || detected == ConfigContentType.MULTI_REPO) refresh(source.id) else rebuild()
    }
    suspend fun update(source: SourceRecord) {
        if (!source.local && source.url.toHttpUrlOrNull() == null) throw SourceException(ErrorCode.INVALID_URL)
        val previous = store.get(source.id); store.save(source)
        if (previous?.url != source.url || previous?.format != source.format) refresh(source.id, true) else rebuild()
    }
    suspend fun delete(id: String) { store.delete(id); rebuild() }
    suspend fun refreshAll() = refreshMutex.withLock {
        supervisorScope {
            val semaphore = Semaphore(3)
            store.all().filter { it.enabled && (!it.local || it.format == "BUNDLE" || it.format == "AUTO") }.map { source ->
                async { semaphore.withPermit { refresh(source.id) } }
            }.awaitAll()
        }
    }
    private suspend fun resolve(source: SourceRecord, cache: ConfigCache, fetch: Boolean): ConfigResolver.Resolution {
        val memo = mutableMapOf<String, ConfigCache?>()
        return ConfigResolver { origin, policy ->
            val url = SourceFingerprint.normalizedUrl(origin.originUrl)
            if (memo.containsKey(url)) memo[url] else {
                val id = "${source.id}:${SourceFingerprint.sha256(url)}"
                val old = store.cache(id)
                val result = if (!fetch || store.get(source.id)?.enabled != true) old else try {
                    val response = withTimeout(policy.timeoutSeconds * 1000L) { network.download(origin.originUrl, old?.etag.orEmpty(), old?.lastModified.orEmpty(), policy.timeoutSeconds) }
                    val body = response.body ?: old?.body ?: throw SourceException(ErrorCode.DOWNLOAD)
                    withContext(Dispatchers.Default) {
                        if (ContentTypeDetector().detect(body, origin.originUrl, response.contentType) == ConfigContentType.UNKNOWN) throw SourceException(ErrorCode.CONFIG_FORMAT)
                    }
                    ConfigCache(id, body, response.etag, response.lastModified, SourceFingerprint.sha256(body), System.currentTimeMillis(), origin.originUrl,
                        response.contentType, System.currentTimeMillis(), latencyMs = response.latencyMs).also { store.saveCache(it) }
                } catch (error: Exception) {
                    error.rethrowCancellation()
                    old?.copy(lastAttemptTime = System.currentTimeMillis(), error = error.friendlyCode())?.also { store.saveCache(it) }
                        ?: ConfigCache(id, "", url = origin.originUrl, lastAttemptTime = System.currentTimeMillis(), error = error.friendlyCode()).also { store.saveCache(it) }
                }
                memo[url] = result; result
            }
        }.resolve(cache.body, source.origin.copy(originUrl = cache.url.ifBlank { source.url }), cache.contentType)
    }
    suspend fun refresh(id: String, unconditional: Boolean = false) = locks.getOrPut(id) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            val source = store.get(id) ?: return@withContext
            try {
                store.status(id, "REFRESHING")
                val old = store.cache(id)
                val cache = if (source.local) old ?: throw SourceException(ErrorCode.STORAGE) else {
                    val response = withTimeout(15_000) { network.download(source.url, if (unconditional) "" else old?.etag.orEmpty(), if (unconditional) "" else old?.lastModified.orEmpty()) }
                    val body = response.body ?: old?.body ?: throw SourceException(ErrorCode.DOWNLOAD)
                    ConfigCache(id, body, response.etag, response.lastModified, SourceFingerprint.sha256(body), System.currentTimeMillis(), source.url, response.contentType, latencyMs = response.latencyMs)
                }
                val result = withTimeout(120_000) { resolve(source, cache, true) }
                val now = System.currentTimeMillis()
                store.finishRefresh(id, cache.copy(lastSuccessTime = now), result.config.warnings.size, now)
                logger.event(LogCategory.CONFIG, "refresh_success")
            } catch (error: Exception) {
                error.rethrowCancellation()
                store.status(id, "ERROR", error.friendlyCode())
                logger.event(LogCategory.CONFIG, "refresh_failed", error)
            }
            rebuild()
        }
    }
    suspend fun clearRemoteCache() { store.clearRemoteCache(); rebuild(); refreshAll() }
    suspend fun warnings(id: String): List<ConfigWarning> = withContext(Dispatchers.IO) {
        val source = store.get(id) ?: return@withContext emptyList()
        val cache = store.cache(id) ?: return@withContext emptyList()
        resolve(source, cache, false).config.warnings
    }
    private suspend fun rebuild() = rebuildMutex.withLock { withContext(Dispatchers.IO) {
        val resolutions = store.all().filter { it.enabled }.mapNotNull { source ->
            val cache = store.cache(source.id) ?: return@mapNotNull null
            try { resolve(source, cache, false) }
            catch (error: Exception) { error.rethrowCancellation(); logger.event(LogCategory.CONFIG, "cache_parse_failed", error); null }
        }
        _config.value = withContext(Dispatchers.Default) { ConfigMerger().merge(resolutions.map { it.config }) }
        _diagnostics.value = resolutions.flatMap { it.diagnostics }
        val bundle = resolutions.firstOrNull { it.statistics.bundleSourceCount > 0 }?.statistics ?: BundleStatistics()
        val sites = _config.value.sites
        _statistics.value = bundle.copy(deduplicatedSiteCount = sites.size, supportedSiteCount = sites.count { it.capability.status == CapabilityStatus.SUPPORTED },
            partialSiteCount = sites.count { it.capability.status == CapabilityStatus.PARTIAL }, unsupportedSiteCount = sites.count { it.capability.status == CapabilityStatus.UNSUPPORTED }, brokenSiteCount = sites.count { it.capability.status == CapabilityStatus.BROKEN })
    } }
}
