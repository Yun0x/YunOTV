package com.yuno.tv.core.config

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import com.yuno.tv.core.hexDigest

object SourceFingerprint {
    fun normalizedUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: return value.trim()
        val pairs = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
            .distinct().sortedWith(compareBy({ it.first }, { it.second.orEmpty() }))
        val builder = url.newBuilder().query(null).fragment(null)
        pairs.forEach { builder.addQueryParameter(it.first, it.second) }
        val built = builder.build()
        val base = "${built.scheme}://${built.host.let { if (it.contains(':')) "[$it]" else it }}" +
            (if (built.port != (if (built.scheme == "https") 443 else 80)) ":${built.port}" else "") +
            built.encodedPath.trimEnd('/')
        // Credentials are meaningful to a source, but are never included in diagnostics.
        val authority = if (built.encodedUsername.isNotEmpty() || built.encodedPassword.isNotEmpty())
            base.replace("://", "://${built.encodedUsername}:${built.encodedPassword}@") else base
        return authority + (built.encodedQuery?.let { "?$it" } ?: "")
    }
    fun normalizedExt(ext: String): String = runCatching { com.yuno.tv.core.ensureSafeJson(ext); canonical(JsonParser.parseString(ext)).toString() }
        .getOrElse { ext.trim() }
    private fun canonical(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply { element.asJsonObject.entrySet().sortedBy { it.key }
            .forEach { add(it.key, canonical(it.value)) } }
        element.isJsonArray -> JsonArray().apply { element.asJsonArray.forEach { add(canonical(it)) } }
        else -> element
    }
    fun create(type: String, api: String, ext: String = ""): String = sha256("$type|${normalizedUrl(api)}|${normalizedExt(ext)}")
    fun sha256(value: String): String = hexDigest(value)
}
