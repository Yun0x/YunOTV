package com.yuno.tv.core.config

import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** No external code executes here. Load is injected so offline and remote resolution share one path. */
class ConfigResolver(private val load: suspend (SourceOrigin, MergePolicy) -> ConfigCache?) {
    data class Resolution(val config: UnifiedConfig, val diagnostics: List<ConfigDiagnostic>, val statistics: BundleStatistics)
    suspend fun resolve(body: String, origin: SourceOrigin, header: String = "", policy: MergePolicy = MergePolicy()): Resolution {
        val configs = mutableListOf<UnifiedConfig>(); val diagnostics = mutableListOf<ConfigDiagnostic>()
        val documents = mutableMapOf<String, ConfigCache?>()
        val failures = mutableMapOf<String, ErrorCode>()
        suspend fun document(from: SourceOrigin, rule: MergePolicy): ConfigCache? {
            val key = SourceFingerprint.normalizedUrl(from.originUrl)
            failures[key]?.let { throw SourceException(it) }
            if (!documents.containsKey(key)) try { documents[key] = load(from, rule) }
            catch (error: Exception) { error.rethrowCancellation(); failures[key] = error.friendlyCode(); throw error }
            return documents[key]
        }
        val resolvedUrls = mutableSetOf<String>()
        val visited = mutableSetOf<String>(); var count = 0; var sites = 0; var bundleCount = 0; var enabledCount = 0
        suspend fun visit(text: String, from: SourceOrigin, contentType: String, depth: Int, currentPolicy: MergePolicy, path: Set<String>) {
            if (depth > currentPolicy.maxDepth || count >= MAX_CONFIGS || sites >= MAX_SITES) {
                configs += UnifiedConfig(warnings = listOf(ConfigWarning(ErrorCode.CONFIG_FORMAT, from.sourceName))); return
            }
            count++
            val detected = withContext(Dispatchers.Default) { ContentTypeDetector().detect(text, from.originUrl, contentType) }
            when (detected) {
                ConfigContentType.YUNO_BUNDLE, ConfigContentType.MULTI_REPO -> {
                    val bundle = if (detected == ConfigContentType.YUNO_BUNDLE) withContext(Dispatchers.Default) { YunoSourceBundleParser().parse(text, from.originUrl) } else null
                    val entries = bundle?.sources ?: withContext(Dispatchers.Default) { MultiRepoParser().parse(text, from.originUrl) }
                    resolvedUrls += SourceFingerprint.normalizedUrl(from.originUrl)
                    val rule = if (depth == 0 && bundle != null) bundle.mergePolicy else (bundle?.mergePolicy ?: currentPolicy).copy(maxDepth = minOf(bundle?.mergePolicy?.maxDepth ?: currentPolicy.maxDepth, currentPolicy.maxDepth))
                    if (depth == 0) { bundleCount = entries.size; enabledCount = entries.count { it.enabled } }
                    for (entry in entries.filter { it.enabled }.sortedByDescending { it.priority }) {
                        val url = SourceFingerprint.normalizedUrl(entry.url)
                        if (url in path || count >= MAX_CONFIGS || depth >= rule.maxDepth) { configs += UnifiedConfig(warnings = listOf(ConfigWarning(ErrorCode.CONFIG_FORMAT, entry.name))); continue }
                        val child = SourceOrigin(from.sourceId, entry.name, entry.url, if (bundle != null) entry.priority else from.priority)
                        val start = System.currentTimeMillis()
                        try {
                            val cache = document(child, rule) ?: continue
                            val before = sites
                            if (cache.body.isBlank() && cache.error != null) throw SourceException(cache.error ?: ErrorCode.DOWNLOAD)
                            visit(cache.body, child, cache.contentType, depth + 1, rule, path + url)
                            if (visited.add(url)) diagnostics += ConfigDiagnostic(url, entry.name, entry.url, child.priority,
                                if (cache.error == null) "OK" else "ERROR", cache.latencyMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - start), sites - before,
                                error = cache.error, cached = cache.error != null)
                        } catch (error: Exception) {
                            error.rethrowCancellation()
                            if (visited.add(url)) diagnostics += ConfigDiagnostic(url, entry.name, entry.url, child.priority, "ERROR",
                                System.currentTimeMillis() - start, error = error.friendlyCode())
                            configs += UnifiedConfig(warnings = listOf(ConfigWarning(error.friendlyCode(), entry.name)))
                        }
                    }
                }
                else -> {
                    val configIndex = configs.size
                    var parsed = withContext(Dispatchers.Default) {
                        when (detected) {
                            ConfigContentType.TVBOX -> TvBoxConfigParser().parse(text, from)
                            ConfigContentType.GENERIC_JSON -> GenericJsonParser().parse(text, from)
                            ConfigContentType.M3U -> M3uConfigParser().parse(text, from)
                            ConfigContentType.TXT -> TxtConfigParser().parse(text, from)
                            else -> throw SourceException(ErrorCode.CONFIG_FORMAT)
                        }
                    }
                    parsed = CapabilityDetector().apply(parsed.copy(sites = parsed.sites.take((MAX_SITES - sites).coerceAtLeast(0))))
                    resolvedUrls += SourceFingerprint.normalizedUrl(from.originUrl)
                    sites += parsed.sites.size
                    configs += parsed
                    // Only a small bounded batch of playlist references is fetched. Never crawl media segments.
                    for (live in (parsed.lives + parsed.sites.filter { it.capability.kind == CapabilityKind.LIVE_M3U }.map { UnifiedLive(it.id, it.name, it.api, origin = it.origin) }).filter { it.url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.let { p -> p.endsWith(".m3u") || p.endsWith(".txt") } == true }.take(4)) {
                        val url = SourceFingerprint.normalizedUrl(live.url)
                        if (url in path || count >= MAX_CONFIGS) continue
                        try { document(from.copy(originUrl = live.url), currentPolicy)?.let { cache ->
                            visit(cache.body, from.copy(originUrl = live.url), cache.contentType, depth + 1, currentPolicy, path + url)
                        } } catch (error: Exception) { error.rethrowCancellation(); configs += UnifiedConfig(warnings = listOf(ConfigWarning(error.friendlyCode(), live.name))) }
                    }
                    // A playlist URL itself is not a playable channel.
                    configs[configIndex] = parsed.copy(lives = parsed.lives.filterNot { it.url.substringBefore('?').let { p -> p.endsWith(".m3u") || p.endsWith(".txt") } })
                }
            }
        }
        visit(body, origin, header, 0, policy, setOf(SourceFingerprint.normalizedUrl(origin.originUrl)))
        val merged = withContext(Dispatchers.Default) { ConfigMerger().merge(configs) }
        val byStatus = merged.sites.groupingBy { it.capability.status }.eachCount()
        val stats = BundleStatistics(bundleCount, enabledCount, diagnostics.count { it.status == "OK" }, diagnostics.count { it.status == "ERROR" },
            resolvedUrls.size, sites, merged.sites.size, byStatus[CapabilityStatus.SUPPORTED] ?: 0, byStatus[CapabilityStatus.PARTIAL] ?: 0,
            byStatus[CapabilityStatus.UNSUPPORTED] ?: 0, byStatus[CapabilityStatus.BROKEN] ?: 0, System.currentTimeMillis())
        return Resolution(merged, diagnostics.map { diagnostic ->
            val own = merged.sites.filter { site -> site.origins.any { it.originUrl == diagnostic.url } }
            diagnostic.copy(supported = own.count { it.supported }, unsupported = own.count { !it.supported })
        }, stats)
    }
    companion object { const val MAX_CONFIGS = 32; const val MAX_SITES = 5000 }
}
