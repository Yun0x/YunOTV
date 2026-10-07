package com.yuno.tv.core.network

import android.content.Context
import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.ErrorCode
import okhttp3.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class ConfigDownload(val body: String?, val etag: String, val lastModified: String, val notModified: Boolean, val contentType: String = "", val latencyMs: Long = 0L)

class NetworkClient(context: Context) {
    val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(java.io.File(context.cacheDir, "http"), 8L * 1024 * 1024)).build()

    suspend fun download(url: String, etag: String = "", lastModified: String = "", timeoutSeconds: Int = 12): ConfigDownload = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", "YunoTV/0.2").apply {
            if (etag.isNotBlank()) header("If-None-Match", etag)
            if (lastModified.isNotBlank()) header("If-Modified-Since", lastModified)
        }.build()
        val response = http.newCall(request).apply { timeout().timeout(timeoutSeconds.toLong(), TimeUnit.SECONDS) }.await()
        response.use {
            if (it.code == 304) ConfigDownload(null, it.header("ETag") ?: etag,
                it.header("Last-Modified") ?: lastModified, true, it.header("Content-Type").orEmpty())
            else {
                if (!it.isSuccessful) throw SourceException(ErrorCode.DOWNLOAD)
                val body = it.body ?: throw SourceException(ErrorCode.EMPTY)
                val bytes = body.byteStream().use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > MAX_CONFIG_BYTES) throw SourceException(ErrorCode.CONFIG_FORMAT)
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                }
                ConfigDownload(decodeUtf8(bytes), it.header("ETag").orEmpty(), it.header("Last-Modified").orEmpty(), false, it.header("Content-Type").orEmpty())
            }
        }.copy(latencyMs = System.currentTimeMillis() - started)
    }
    companion object {
        const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
        fun decodeUtf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) { throw SourceException(ErrorCode.ENCODING) }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
