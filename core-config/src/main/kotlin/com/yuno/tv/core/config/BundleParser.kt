package com.yuno.tv.core.config

import com.google.gson.*
import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MergePolicy(val deduplicate: Boolean = true, val preferHealthy: Boolean = true, val timeoutSeconds: Int = 12, val maxDepth: Int = 4)
data class YunoSourceEntry(val name: String, val url: String, val priority: Int = 100, val enabled: Boolean = true)
data class YunoSourceBundle(val format: String = "yunotv-source-bundle", val version: Int = 1,
    val mergePolicy: MergePolicy = MergePolicy(), val sources: List<YunoSourceEntry> = emptyList())
class YunoSourceBundleParser {
    fun parse(body: String, baseUrl: String = ""): YunoSourceBundle {
        val root = jsonObject(body)
        if (root.string("format") != "yunotv-source-bundle" || root.int("version", 1) != 1) throw SourceException(ErrorCode.CONFIG_FORMAT)
        val policy = root.getAsJsonObjectSafe("mergePolicy")
        val entries = root.arraySafe("sources").take(64).mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val entry = it.asJsonObject
            val raw = entry.string("url"); val url = RelativeUrlResolver.resolve(baseUrl, raw)
            if (url.toHttpUrlOrNull() == null) return@mapNotNull null
            YunoSourceEntry(entry.string("name").ifBlank { url.toHttpUrlOrNull()!!.host }, url,
                entry.int("priority", 100), entry.bool("enabled", true))
        }
        if (root.arraySafe("sources").size() > 0 && entries.isEmpty()) throw SourceException(ErrorCode.INVALID_URL)
        return YunoSourceBundle(mergePolicy = MergePolicy(policy.bool("deduplicate", true), policy.bool("preferHealthy", true),
            policy.int("timeoutSeconds", 12).coerceIn(3, 30), policy.int("maxDepth", 4).coerceIn(1, 8)), sources = entries)
    }
}
object RelativeUrlResolver {
    fun resolve(base: String, value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        if (raw.toHttpUrlOrNull() != null) return raw.toHttpUrlOrNull()!!.toString()
        if (raw.startsWith("./") || raw.startsWith("../") || raw.startsWith('/') || (!raw.contains(':') && (raw.contains('/') || raw.substringBefore(';').substringBefore('?').endsWith(".js") || raw.substringBefore(';').endsWith(".jar") || raw.endsWith(".json") || raw.endsWith(".m3u") || raw.endsWith(".txt")))) {
            return base.toHttpUrlOrNull()?.resolve(raw)?.toString() ?: raw
        }
        return raw
    }
}
enum class ConfigContentType { COMPILED_BUNDLE, YUNO_BUNDLE, TVBOX, MULTI_REPO, GENERIC_JSON, M3U, TXT, UNKNOWN }
class ContentTypeDetector {
    fun detect(body: String, url: String = "", header: String = ""): ConfigContentType {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.startsWith("#EXTM3U") || text.startsWith("#EXT-X-")) return ConfigContentType.M3U
        if (text.startsWith('{') || text.startsWith('[')) {
            val root = runCatching { jsonObject(text) }.getOrNull() ?: return ConfigContentType.UNKNOWN
            return when {
                root.string("format") == "yunotv-compiled-bundle" -> ConfigContentType.COMPILED_BUNDLE
                root.string("format") == "yunotv-source-bundle" -> ConfigContentType.YUNO_BUNDLE
                root.has("sites") || root.has("lives") || root.has("parses") -> ConfigContentType.TVBOX
                root.has("urls") || root.has("configs") -> ConfigContentType.MULTI_REPO
                root.has("sources") || root.has("channels") -> ConfigContentType.GENERIC_JSON
                else -> ConfigContentType.UNKNOWN
            }
        }
        if (text.startsWith('<') || header.contains("text/html", true)) return ConfigContentType.UNKNOWN
        return if (text.lineSequence().any { it.contains("http", true) } || url.substringBefore('?').endsWith(".txt")) ConfigContentType.TXT else ConfigContentType.UNKNOWN
    }
}
class MultiRepoParser {
    fun parse(body: String, base: String): List<YunoSourceEntry> {
        val root = jsonObject(body)
        val entries = if (root.has("urls")) root.arraySafe("urls") else root.arraySafe("configs")
        return entries.take(64).mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            val url = RelativeUrlResolver.resolve(base, obj?.string("url") ?: element.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            if (url.toHttpUrlOrNull() == null) null else YunoSourceEntry(obj?.string("name").orEmpty().ifBlank { url.toHttpUrlOrNull()!!.host }, url,
                obj?.int("priority", 100) ?: 100, obj?.bool("enabled", true) ?: true)
        }
    }
}
internal fun jsonObject(body: String): JsonObject {
    ensureSafeJson(body)
    return try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { throw SourceException(ErrorCode.CONFIG_FORMAT) }
}
internal fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.int(key: String, default: Int) = string(key).toIntOrNull() ?: default
internal fun JsonObject.bool(key: String, default: Boolean) = if (!has(key)) default else string(key) in listOf("true", "1")
internal fun JsonObject.arraySafe(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
internal fun JsonObject.getAsJsonObjectSafe(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
