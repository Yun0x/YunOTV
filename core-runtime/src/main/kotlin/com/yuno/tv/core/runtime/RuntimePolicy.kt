package com.yuno.tv.core.runtime

import com.google.gson.*
import java.security.MessageDigest

object RuntimePolicy {
    const val JS_BYTES = 1024 * 1024
    const val JAR_BYTES = 24 * 1024 * 1024
    const val RESULT_BYTES = 192 * 1024
    const val HTTP_BYTES = 256 * 1024
    const val DEADLINE_MS = 12_000L
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun requireSize(bytes: ByteArray, maximum: Int): ByteArray = bytes.also { require(it.size <= maximum) { "INPUT_LIMIT" } }
}
internal fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
class RuntimeFailure(val reason: String) : RuntimeException(reason)
interface RuntimeHost {
    fun http(url: String, options: String): String
    fun storage(operation: String, key: String, value: String): String
}
