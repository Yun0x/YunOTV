package com.yuno.tv.core.runtime

import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** Content addressed artifacts, conditional refresh and last-good fallback. URLs never become paths. */
class ArtifactRepository(private val directory: File, client: OkHttpClient, private val maximum: Int, private val capacity: Long, private val validate: (ByteArray) -> Unit = {}) {
    data class Artifact(val url: String, val hash: String, val etag: String, val lastModified: String, val lastSuccess: Long)
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val failures=linkedMapOf<String,Long>()
    suspend fun get(input: String, refresh: Boolean = false): Pair<Artifact, File> = lock.withLock {
        withContext(Dispatchers.IO) {
            val url = input.substringBefore(";md5;").trim().toHttpUrlOrNull()?.toString() ?: throw RuntimeFailure("INVALID_URL")
            if(System.currentTimeMillis()-(failures[url] ?: 0)<120_000)throw RuntimeFailure("DOWNLOAD_FAILED")
            directory.mkdirs()
            val index = directory.resolve(RuntimePolicy.hash(url.toByteArray()) + ".json")
            val previous = runCatching {
                require(index.length()<=16384)
                Gson().fromJson(index.readText(),Artifact::class.java).also { require(it.hash.matches(Regex("[a-f0-9]{64}"))) }
            }.getOrNull()
            val cached = previous?.let { directory.resolve(it.hash) }?.takeIf { it.isFile && it.length()<=maximum && RuntimePolicy.hash(it.readBytes())==previous.hash }
            if (cached != null && !refresh && System.currentTimeMillis() - previous.lastSuccess < 6 * 3600_000) return@withContext previous to cached
            val request = Request.Builder().url(url).apply {
                previous?.etag?.takeIf { cached!=null && it.isNotBlank() }?.let { header("If-None-Match", it) }
                previous?.lastModified?.takeIf { cached!=null && it.isNotBlank() }?.let { header("If-Modified-Since", it) }
            }.build()
            try {
                download(request).let { response ->
                    if (response.code == 304 && previous != null && cached != null) {
                        val current = previous.copy(lastSuccess = System.currentTimeMillis()); writeIndex(index, current); return@withContext current to cached
                    }
                    if (!response.isSuccessful) throw RuntimeFailure("DOWNLOAD_FAILED")
                    val bytes = response.bytes
                    validate(bytes)
                    val hash = RuntimePolicy.hash(bytes)
                    val expected = input.substringAfter(";md5;", "").lowercase()
                    if (expected.matches(Regex("[a-f0-9]{32}"))) {
                        val actual = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                        if (expected != actual) throw RuntimeFailure("HASH_MISMATCH")
                    }
                    val file = directory.resolve(hash)
                    if (!file.isFile) { val temporary = directory.resolve("$hash.tmp"); temporary.writeBytes(bytes); check(temporary.renameTo(file)) }
                    val current = Artifact(url, hash, response.etag.take(8192), response.lastModified.take(512), System.currentTimeMillis())
                    writeIndex(index, current); trim(hash)
                    current to file
                }
            } catch (error: Exception) {
                if(error is kotlinx.coroutines.CancellationException)throw error
                if (previous != null && cached != null) previous to cached else { failures[url]=System.currentTimeMillis();while(failures.size>256)failures.remove(failures.keys.first());throw error }
            }
        }
    }
    private data class Download(val code:Int,val bytes:ByteArray,val etag:String,val lastModified:String) { val isSuccessful get()=code in 200..299 }
    private suspend fun download(request:Request):Download = suspendCancellableCoroutine { continuation ->
        val call=http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,error:java.io.IOException) { if(continuation.isActive)continuation.resumeWithException(RuntimeFailure("DOWNLOAD_FAILED")) }
            override fun onResponse(call:Call,response:Response) {
                try { response.use {
                    if((it.body?.contentLength() ?: 0)>maximum)throw RuntimeFailure("INPUT_LIMIT")
                    val bytes=if(it.code==304)byteArrayOf()else it.body?.byteStream()?.use { stream -> readBounded(stream,maximum) } ?: byteArrayOf()
                    val result=Download(it.code,bytes,it.header("ETag").orEmpty(),it.header("Last-Modified").orEmpty())
                    if(continuation.isActive)continuation.resume(result)
                } } catch(error:Exception) { if(continuation.isActive)continuation.resumeWithException(error) }
            }
        })
    }
    private fun writeIndex(file: File, value: Artifact) { val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(Gson().toJson(value)); check(tmp.renameTo(file)) }
    private fun trim(retained: String) {
        val artifacts = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }.sortedBy { it.lastModified() }
        var size = artifacts.sumOf { it.length() }
        artifacts.filter { it.name != retained }.forEach { if (size > capacity) { size -= it.length(); it.delete() } }
    }
    suspend fun clear() = lock.withLock { withContext(Dispatchers.IO) { directory.deleteRecursively();failures.clear(); Unit } }
    fun stats() = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }.let { it.size to it.sumOf(File::length) }
    companion object {
        fun readBounded(stream: java.io.InputStream, maximum: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; if (out.size() + count > maximum) throw RuntimeFailure("INPUT_LIMIT"); out.write(buffer, 0, count) }
            return out.toByteArray()
        }
    }
}
class JarRepository(directory: File, client: OkHttpClient) {
    private val delegate = ArtifactRepository(directory, client, RuntimePolicy.JAR_BYTES, 96L * 1024 * 1024, { JarVerifier().validate(it) })
    suspend fun get(url: String, refresh: Boolean = false) = withContext(Dispatchers.IO) { delegate.get(url, refresh).also { JarVerifier().validate(it.second.readBytes()) } }
    suspend fun clear() = delegate.clear()
    fun stats() = delegate.stats()
}
