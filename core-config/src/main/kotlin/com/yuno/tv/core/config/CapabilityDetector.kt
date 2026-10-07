package com.yuno.tv.core.config

import com.yuno.tv.core.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CapabilityDetector {
    fun detect(site: UnifiedSite): SourceCapability {
        val api = site.api.lowercase(); val ext = site.ext.lowercase()
        val kind = when {
            api.contains("drpy") || api.endsWith(".js") || ext.substringBefore('?').endsWith(".js") -> CapabilityKind.DRPY_JS
            api.startsWith("csp_") || api.contains(".jar") -> CapabilityKind.SPIDER_JAR
            site.type == 3 -> if (site.metadata.spider.isNotBlank()) CapabilityKind.SPIDER_JAR else CapabilityKind.TVBOX_TYPE3
            api.substringBefore('?').endsWith(".m3u") -> CapabilityKind.LIVE_M3U
            listOf(".mp4", ".m3u8", ".ts", ".webm", ".mkv").any { api.substringBefore('?').endsWith(it) } -> CapabilityKind.DIRECT_MEDIA
            site.type == 0 -> CapabilityKind.TVBOX_TYPE0
            site.type == 1 && site.api.toHttpUrlOrNull() != null -> CapabilityKind.CMS_API
            site.type == 1 -> CapabilityKind.TVBOX_TYPE1
            site.origin.originUrl.startsWith("local:") && site.api.startsWith("local:" ) -> CapabilityKind.LOCAL
            else -> CapabilityKind.UNKNOWN
        }
        val status = when {
            kind == CapabilityKind.CMS_API || kind == CapabilityKind.DIRECT_MEDIA -> CapabilityStatus.SUPPORTED
            kind == CapabilityKind.LIVE_M3U -> CapabilityStatus.PARTIAL
            site.api.isBlank() -> CapabilityStatus.BROKEN
            else -> CapabilityStatus.UNSUPPORTED
        }
        return SourceCapability(kind, status, if (status == CapabilityStatus.BROKEN) ErrorCode.INVALID_URL else if (status == CapabilityStatus.UNSUPPORTED) ErrorCode.UNSUPPORTED else null)
    }
    fun apply(config: UnifiedConfig): UnifiedConfig = config.copy(sites = config.sites.map { site ->
        val capability = detect(site)
        site.copy(capability = capability, supported = capability.status == CapabilityStatus.SUPPORTED)
    })
}
class GenericJsonParser {
    fun parse(body: String, origin: SourceOrigin): UnifiedConfig {
        val root = jsonObject(body)
        val converted = com.google.gson.JsonObject().apply {
            add("sites", com.google.gson.JsonArray().apply {
                root.arraySafe("sources").take(5000).forEach { element ->
                    if (element.isJsonObject) {
                        val entry = element.asJsonObject.deepCopy()
                        if (!entry.has("api")) entry.addProperty("api", entry.string("url"))
                        if (entry.string("type").toIntOrNull() == null) entry.addProperty("type", 1)
                        if (!entry.has("key")) entry.addProperty("key", entry.string("id"))
                        add(entry)
                    }
                }
            }); add("lives", root.arraySafe("channels"))
        }
        return TvBoxConfigParser().parse(converted.toString(), origin)
    }
}
