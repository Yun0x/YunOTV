package com.yuno.tv.core.network

import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AggregatedSearch(val results: List<UnifiedVod>, val errors: Map<String, ErrorCode>,
    val variants: Map<String, List<UnifiedVod>> = emptyMap(), val completed: Int = 0, val total: Int = 0, val finished: Boolean = true)
class SearchAggregator(private val providerFor: (UnifiedSite) -> SourceProvider, private val timeoutMs: Long = 12_000,
    private val order: (List<UnifiedSite>) -> List<UnifiedSite> = { it }, private val dwellMs: Long = 6_000) {
    constructor(registry: ProviderRegistry) : this(registry::provider, order = registry.healthChecker::ordered)
    suspend fun search(keyword: String, sites: List<UnifiedSite>): AggregatedSearch = stream(keyword, sites).last()
    fun stream(keyword: String, sites: List<UnifiedSite>, more: Boolean = false): Flow<AggregatedSearch> = channelFlow {
        val selected = order(sites.filter { it.supported && it.searchable }).take(128)
        val semaphore = Semaphore(3); val mutex = Mutex()
        val representatives = linkedMapOf<String, UnifiedVod>()
        val responses = mutableMapOf<String, List<UnifiedVod>>(); val errors = mutableMapOf<String, ErrorCode>()
        suspend fun snapshot(finished: Boolean): AggregatedSearch = withContext(Dispatchers.Default) {
            val unique = selected.flatMap { responses[it.id].orEmpty() }.distinctBy { it.identity }.take(3000)
            val groups = unique.groupBy { vod -> MediaFingerprint.of(vod) }
            groups.forEach { (key, variants) -> representatives.putIfAbsent(key, variants.first()) }
            val ordered = representatives.filterKeys { it in groups }.map { (key, first) -> first to groups.getValue(key) }
            AggregatedSearch(ordered.map { it.first }, errors.toMap(), ordered.associate { it.first.identity to it.second }, responses.size, if(finished)responses.size else selected.size, finished)
        }
        send(snapshot(selected.isEmpty()))
        val finished = withTimeoutOrNull(30_000) {
            supervisorScope {
                suspend fun tier(values:List<UnifiedSite>) { values.map { site -> async {
                    semaphore.withPermit {
                        val result=networkResult { withTimeout(timeoutMs) { providerFor(site).search(keyword) } }
                        mutex.withLock {
                            responses[site.id]=(result as? NetworkResult.Success)?.data.orEmpty()
                            if(result is NetworkResult.Error)errors[site.id]=result.code
                            send(snapshot(false))
                        }
                    }
                } }.awaitAll() }
                tier(selected.take(16))
                if(selected.size>16) {
                    if(representatives.size>=24 && !more) {
                        send(snapshot(true)) // First useful results are ready while the user browses.
                        delay(dwellMs) // Leaving the screen or editing the query cancels this expansion.
                    }
                    tier(selected.drop(16).take(if(more)112 else 32))
                }
            }; true
        } ?: false
        if (!finished) selected.filter { !responses.containsKey(it.id) }.forEach { errors[it.id] = ErrorCode.TIMEOUT }
        send(snapshot(true))
    }
}
class SearchUseCase(private val aggregator: SearchAggregator) {
    operator fun invoke(keyword: String, sites: List<UnifiedSite>) = aggregator.stream(keyword.trim().take(100), sites)
}
