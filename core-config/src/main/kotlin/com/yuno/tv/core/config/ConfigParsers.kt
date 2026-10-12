package com.yuno.tv.core.config

import com.google.gson.*
import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** External DTO is confined to this parsing boundary. No Spider code is executed. */
data class TVBoxConfig(val sites: JsonArray, val lives: JsonArray, val parses: JsonArray)

class TvBoxConfigParser {
    fun parse(body: String, origin: SourceOrigin): UnifiedConfig {
        com.yuno.tv.core.ensureSafeJson(body)
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrElse {
            throw SourceException(ErrorCode.CONFIG_FORMAT)
        }
        val dto = TVBoxConfig(root.array("sites"), root.array("lives"), root.array("parses"))
        if (!root.has("sites") && !root.has("lives") && !root.has("parses")) throw SourceException(ErrorCode.CONFIG_FORMAT)
        val warnings = mutableListOf<ConfigWarning>()
        val sites = dto.sites.take(5000).mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            if (obj == null) { warnings += ConfigWarning(ErrorCode.CONFIG_FORMAT); return@mapNotNull null }
            val api = RelativeUrlResolver.resolve(origin.originUrl, obj.text("api")); val type = obj.integer("type", 1)
            val name = obj.text("name").trim().replace(Regex("\\s+"), " ").ifBlank { obj.text("key") }.ifBlank { "CMS" }
            val rawExt = obj.get("ext")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else it.toString() }.orEmpty()
            val ext = RelativeUrlResolver.resolve(origin.originUrl, rawExt)
            val supported = type == 1 && api.toHttpUrlOrNull() != null
            if (!supported) warnings += ConfigWarning(ErrorCode.UNSUPPORTED, name)
            UnifiedSite(SourceFingerprint.create(type.toString(), api, ext), obj.text("key").ifBlank { name },
                name, type, api, obj.flag("searchable", true), obj.flag("quickSearch", true),
                obj.flag("filterable", false), ext, origin, supported, metadata = SourceMetadata(spider = RelativeUrlResolver.resolve(origin.originUrl, obj.text("jar").ifBlank { root.text("spider") }), headers = (obj.getAsJsonObjectSafe("headers").entrySet() + obj.getAsJsonObjectSafe("header").entrySet()).filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }))
        }
        val lives = mutableListOf<UnifiedLive>()
        fun addLive(obj: JsonObject, group: String = "IPTV", depth: Int = 0) {
            if (depth > 8 || lives.size >= 5000) { warnings += ConfigWarning(ErrorCode.CONFIG_FORMAT); return }
            if (obj.has("channels")) { obj.array("channels").forEach { if (it.isJsonObject) addLive(it.asJsonObject, obj.text("name").trim().replace(Regex("\\s+"), " ").ifBlank { group }, depth + 1) }; return }
            val url = RelativeUrlResolver.resolve(origin.originUrl, obj.text("url").ifBlank { obj.array("urls").firstOrNull()?.let { if (it.isJsonPrimitive) it.asString else "" }.orEmpty() })
            if (url.toHttpUrlOrNull() == null) { warnings += ConfigWarning(ErrorCode.INVALID_URL, obj.text("name")); return }
            lives += UnifiedLive(SourceFingerprint.create("live", url), obj.text("name").trim().replace(Regex("\\s+"), " ").ifBlank { "IPTV" }, url,
                group, RelativeUrlResolver.resolve(origin.originUrl, obj.text("logo")), RelativeUrlResolver.resolve(origin.originUrl, obj.text("epg")), origin)
        }
        dto.lives.take(5000).forEach { if (it.isJsonObject) addLive(it.asJsonObject) }
        val parses = dto.parses.take(100).mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val obj = it.asJsonObject; val url = RelativeUrlResolver.resolve(origin.originUrl, obj.text("url"))
            if (url.toHttpUrlOrNull() == null) return@mapNotNull null
            UnifiedParse(SourceFingerprint.create("parse", url, obj.get("ext")?.toString().orEmpty()),
                obj.text("name"), obj.integer("type", 0), url, obj.get("ext")?.toString().orEmpty(), origin)
        }
        return CapabilityDetector().apply(UnifiedConfig(sites, lives, parses, warnings, RelativeUrlResolver.resolve(origin.originUrl, root.text("spider"))))
    }
}

class TxtConfigParser {
    fun parse(body: String, origin: SourceOrigin, forceLive: Boolean = false): UnifiedConfig {
        val sites = mutableListOf<UnifiedSite>(); val lives = mutableListOf<UnifiedLive>()
        val warnings = mutableListOf<ConfigWarning>(); var group = "IPTV"
        body.lineSequence().take(10000).forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith('#') || line.startsWith("//")) return@forEach
            val delimiter = if (line.contains('|')) '|' else ','
            val split = line.split(delimiter, limit = 2)
            val rawUrl = split.last().trim()
            if (rawUrl == "#genre#") { group = split.first().trim(); return@forEach }
            val url = RelativeUrlResolver.resolve(origin.originUrl, rawUrl)
            if (url.toHttpUrlOrNull() == null) {
                // Unmarked prose is treated as a comment; malformed URL-looking rows are reported.
                if (line.contains("://") || split.size > 1) warnings += ConfigWarning(ErrorCode.INVALID_URL)
                return@forEach
            }
            val name = if (split.size == 2) split.first().trim().ifBlank { url.toHttpUrlOrNull()!!.host } else url.toHttpUrlOrNull()!!.host
            val path = url.toHttpUrlOrNull()!!.encodedPath.lowercase()
            if (forceLive || path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".ts"))
                lives += UnifiedLive(SourceFingerprint.create("live", url), name, url, group, origin = origin)
            else sites += UnifiedSite(SourceFingerprint.create("1", url), name, name, 1, url, origin = origin)
        }
        if (sites.isEmpty() && lives.isEmpty()) throw SourceException(ErrorCode.EMPTY)
        return UnifiedConfig(sites, lives, warnings = warnings)
    }
}

class M3uConfigParser {
    fun parse(body: String, origin: SourceOrigin): UnifiedConfig {
        if (body.contains("#EXT-X-TARGETDURATION") || body.contains("#EXT-X-STREAM-INF")) {
            if (origin.originUrl.toHttpUrlOrNull() == null) throw SourceException(ErrorCode.UNSUPPORTED)
            return UnifiedConfig(lives = listOf(UnifiedLive(SourceFingerprint.create("live", origin.originUrl),
                origin.sourceName, origin.originUrl, origin = origin)))
        }
        val lives = mutableListOf<UnifiedLive>(); val warnings = mutableListOf<ConfigWarning>()
        var name = "IPTV"; var group = "IPTV"; var logo = ""; var epg = ""
        body.lineSequence().take(20000).forEach { raw ->
            val line = raw.trim()
            fun attribute(key: String): String = Regex("${Regex.escape(key)}=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
            when {
                line.startsWith("#EXTM3U") -> epg = RelativeUrlResolver.resolve(origin.originUrl, attribute("url-tvg").ifBlank { attribute("x-tvg-url") })
                line.startsWith("#EXTINF") -> { name = line.substringAfter(',', "IPTV"); group = attribute("group-title").ifBlank { "IPTV" }; logo = RelativeUrlResolver.resolve(origin.originUrl, attribute("tvg-logo")) }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    val url = RelativeUrlResolver.resolve(origin.originUrl, line)
                    if (url.toHttpUrlOrNull() != null) lives += UnifiedLive(SourceFingerprint.create("live", url), name, url, group, logo, epg, origin)
                    else warnings += ConfigWarning(ErrorCode.INVALID_URL)
                }
            }
        }
        return UnifiedConfig(lives = lives, warnings = warnings)
    }
}

class ConfigParser {
    fun parse(body: String, origin: SourceOrigin, format: String = "AUTO"): UnifiedConfig {
        if (body.isBlank()) throw SourceException(ErrorCode.EMPTY)
        if (body.contains('\uFFFD')) throw SourceException(ErrorCode.ENCODING)
        val text = body.trim().removePrefix("\uFEFF")
        return when {
            format == "JSON" || text.startsWith('{') -> TvBoxConfigParser().parse(text, origin)
            format == "M3U" || text.startsWith("#EXTM3U") -> M3uConfigParser().parse(text, origin)
            else -> TxtConfigParser().parse(text, origin, format == "IPTV")
        }
    }
}
private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
private fun JsonObject.integer(key: String, default: Int) = text(key).toIntOrNull() ?: default
private fun JsonObject.flag(key: String, default: Boolean) = if (!has(key)) default else text(key) == "1" || text(key) == "true"
