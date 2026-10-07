package com.yuno.tv.core.model

enum class CapabilityKind { CMS_API, DIRECT_MEDIA, LIVE_M3U, TVBOX_TYPE0, TVBOX_TYPE1, TVBOX_TYPE3, DRPY_JS, SPIDER_JAR, LOCAL, UNKNOWN }
enum class CapabilityStatus { SUPPORTED, PARTIAL, UNSUPPORTED, BROKEN }
data class SourceCapability(val kind: CapabilityKind = CapabilityKind.UNKNOWN, val status: CapabilityStatus = CapabilityStatus.UNSUPPORTED, val reason: ErrorCode? = null)
data class SourceMetadata(val spider: String = "", val headers: Map<String, String> = emptyMap())
enum class SourceHealthStatus { AVAILABLE, DEGRADED, UNAVAILABLE, UNSUPPORTED, UNKNOWN }
data class SourceHealth(val status: SourceHealthStatus = SourceHealthStatus.UNKNOWN, val lastChecked: Long = 0,
    val latencyMs: Long = 0, val lastSuccess: Long = 0, val error: ErrorCode? = null,
    val operations: Map<String, OperationHealth> = emptyMap(), val lines: Map<String, OperationHealth> = emptyMap())
data class ConfigDiagnostic(val id: String, val name: String, val url: String, val priority: Int,
    val status: String, val latencyMs: Long = 0, val siteCount: Int = 0, val supported: Int = 0,
    val unsupported: Int = 0, val error: ErrorCode? = null, val cached: Boolean = false)
data class BundleStatistics(val bundleSourceCount: Int = 0, val enabledConfigCount: Int = 0,
    val downloadedConfigCount: Int = 0, val downloadFailedConfigCount: Int = 0, val resolvedConfigCount: Int = 0,
    val parsedSiteCount: Int = 0, val deduplicatedSiteCount: Int = 0, val supportedSiteCount: Int = 0,
    val partialSiteCount: Int = 0, val unsupportedSiteCount: Int = 0, val brokenSiteCount: Int = 0,
    val lastUpdate: Long = 0)
enum class PlaybackKind { HLS, MP4, DIRECT, PARSE_REQUIRED, UNKNOWN }
data class ResolvedPlayback(val url: String, val headers: Map<String, String> = emptyMap(),
    val userAgent: String = "YunoTV/0.3", val referer: String = "", val contentType: String = "", val kind: PlaybackKind = PlaybackKind.UNKNOWN)

// Public domain names remain compatible with the phase-one UnifiedVod/UnifiedLine schema.
typealias UnifiedMediaItem = UnifiedVod
typealias UnifiedMediaDetail = UnifiedVod
typealias UnifiedPlayLine = UnifiedLine
typealias UnifiedLiveChannel = UnifiedLive
data class UnifiedLiveGroup(val id: String, val name: String, val channels: List<UnifiedLiveChannel>)

data class PlaybackCandidate(val url: String, val parseRequired: Boolean = false, val headers: Map<String, String> = emptyMap(),
    val referer: String = "", val userAgent: String = "", val source: String = "", val line: String = "", val episode: String = "", val contentType: String = "")

data class OperationHealth(val success: Int = 0, val failure: Int = 0, val consecutiveFailures: Int = 0, val lastSuccess: Long = 0, val latencyMs: Long = 0) {
    val score: Int get() = (if(lastSuccess>0) 30 else 0) + (success*3).coerceAtMost(30) - (consecutiveFailures*15).coerceAtMost(60) - (latencyMs/1000).toInt().coerceAtMost(20)
}
