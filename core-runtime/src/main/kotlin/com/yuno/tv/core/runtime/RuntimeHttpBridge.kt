package com.yuno.tv.core.runtime

import com.google.gson.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Each instance belongs to one source. Plugins cannot choose another source's cookies/storage. */
class RuntimeHttpBridge(client: OkHttpClient, private val storageFile: File? = null) : RuntimeHost {
    private val cookies = mutableListOf<Cookie>()
    private val http = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).followRedirects(true).cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) { cookies.removeAll { it.expiresAt < System.currentTimeMillis() }; cookies.filter { it.matches(url) } }
        override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
            values.filter { it.toString().length <= 4096 }.forEach { value -> cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }; cookies += value }
            while (cookies.size > 128) cookies.removeAt(0)
        }
    }).build()
    private val activeCalls=java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    fun cancelRequests() { activeCalls.forEach { it.cancel() } }
    private val requests = java.util.concurrent.Semaphore(2)
    private val data = linkedMapOf<String, String>()
    init { runCatching { if (storageFile?.isFile == true && storageFile.length() <= 65536) JsonParser.parseString(storageFile.readText()).asJsonObject.entrySet().take(64).forEach { (k,v) -> data[k] = v.asString } } }
    override fun http(url: String, options: String): String {
        if(!requests.tryAcquire())throw RuntimeFailure("REQUEST_LIMIT")
        try { return request(url,options) } finally { requests.release() }
    }
    private fun request(url: String, options: String): String {
        require(options.length <= 32768)
        val parsed = JsonParser.parseString(options).asJsonObject
        val target = url.toHttpUrlOrNull() ?: throw RuntimeFailure("INVALID_URL")
        val request = Request.Builder().url(target)
        parsed.obj("headers").entrySet().take(32).forEach { (key, value) ->
            val text = value.asString; if (key.matches(Regex("[A-Za-z0-9-]+")) && text.length <= 8192 && !text.contains('\n') && !text.contains('\r')) request.header(key,text)
        }
        val method = parsed.text("method").uppercase().ifBlank { "GET" }
        if (method !in listOf("GET", "POST", "HEAD")) throw RuntimeFailure("UNSUPPORTED_METHOD")
        if (method == "POST") {
            val value = parsed.get("data") ?: parsed.get("body")
            val contentType=parsed.obj("headers").entrySet().firstOrNull { it.key.equals("Content-Type",true) }?.value?.asString.orEmpty()
            val content = if (value?.isJsonObject == true && !contentType.contains("json",true)) FormBody.Builder().apply { value.asJsonObject.entrySet().forEach { (k,v) -> add(k,v.asString) } }.build()
            else (if(value?.isJsonObject==true)value.toString()else value?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()).take(65536).toRequestBody(contentType.ifBlank { "application/x-www-form-urlencoded" }.toMediaTypeOrNull())
            request.post(content)
        } else if (method == "HEAD") request.head()
        val call=http.newCall(request.build());activeCalls.add(call)
        try { return call.execute().use { response ->
            val bytes = response.body?.byteStream()?.use { ArtifactRepository.readBounded(it, RuntimePolicy.HTTP_BYTES) } ?: byteArrayOf()
            val content = if (parsed.text("buffer") == "2") java.util.Base64.getEncoder().encodeToString(bytes) else String(bytes, response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            JsonObject().apply { addProperty("content",content); addProperty("code",response.code); add("headers", Gson().toJsonTree(response.headers.toMultimap())); addProperty("url",response.request.url.toString()) }.toString()
                .also { RuntimePolicy.requireSize(it.toByteArray(),512*1024) }
        } }finally { activeCalls.remove(call) }
    }
    @Synchronized override fun storage(operation: String, key: String, value: String): String {
        require(key.length <= 256 && value.toByteArray().size <= 8192)
        when (operation) {
            "get" -> return data[key].orEmpty()
            "set" -> { if (data.size >= 64 && key !in data) throw RuntimeFailure("STORAGE_LIMIT"); val old=data[key];data[key]=value; if (Gson().toJson(data).toByteArray().size > 65536) { if(old==null)data.remove(key)else data[key]=old;throw RuntimeFailure("STORAGE_LIMIT") } }
            "remove" -> data.remove(key)
            else -> throw RuntimeFailure("UNSUPPORTED_OPERATION")
        }
        storageFile?.let { current ->
            current.parentFile.mkdirs(); val tmp = File(current.parentFile,current.name+".tmp")
            tmp.writeText(Gson().toJson(data));check(tmp.renameTo(current))
            val files=current.parentFile.listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.lastModified() }
            var total=files.sumOf { it.length() }
            files.filter { it!=current }.forEach { if(total>16L*1024*1024) { total-=it.length();it.delete() } }
        }
        return ""
    }
}
