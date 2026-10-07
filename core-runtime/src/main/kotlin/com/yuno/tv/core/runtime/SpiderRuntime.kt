package com.yuno.tv.core.runtime

import android.os.Build
import dalvik.system.InMemoryDexClassLoader
import com.google.gson.*
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import java.lang.reflect.InvocationTargetException

class SpiderClassResolver {
    fun resolve(api: String): String {
        val value = api.substringAfterLast(';').substringAfter("csp_", "")
        if (value.isBlank() || !value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) throw RuntimeFailure("UNSUPPORTED_CLASS")
        return "com.github.catvod.spider.$value"
    }
}
class JarVerifier {
    /** Host-side verification streams ZIP entries; a compression bomb never becomes a large host array. */
    fun validate(bytes: ByteArray) {
        RuntimePolicy.requireSize(bytes,RuntimePolicy.JAR_BYTES)
        var entries=0;var total=0L;var dexCount=0
        ZipInputStream(bytes.inputStream()).use { zip ->
            val chunk=ByteArray(8192)
            while(true) {
                val entry=zip.nextEntry ?: break
                if(++entries>4096 || entry.name.startsWith('/') || entry.name.split('/').any { it==".." } || entry.name.endsWith(".so"))throw RuntimeFailure("UNSAFE_JAR")
                val dex=entry.name.matches(Regex("classes(\\d+)?\\.dex"))
                val header=ByteArray(112);var size=0
                val checksum=java.util.zip.Adler32();val signature=java.security.MessageDigest.getInstance("SHA-1")
                while(true) {
                    val count=zip.read(chunk);if(count<0)break
                    if(dex && size<112)System.arraycopy(chunk,0,header,size,minOf(count,112-size))
                    if(dex) {
                        val from=maxOf(0,12-size);if(from<count)checksum.update(chunk,from,count-from)
                        val shaFrom=maxOf(0,32-size);if(shaFrom<count)signature.update(chunk,shaFrom,count-shaFrom)
                    }
                    size+=count;total+=count
                    if(size>32*1024*1024 || total>64L*1024*1024)throw RuntimeFailure("INPUT_LIMIT")
                }
                if(dex) {
                    val view=ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    if(size<112 || !header.copyOfRange(0,4).contentEquals(byteArrayOf(100,101,120,10)) || header[7].toInt()!=0 ||
                        view.getInt(32)!=size || view.getInt(36)!=112 || view.getInt(8)!=checksum.value.toInt() ||
                        !header.copyOfRange(12,32).contentEquals(signature.digest()))throw RuntimeFailure("INVALID_DEX")
                    if(++dexCount>8)throw RuntimeFailure("INPUT_LIMIT")
                }
            }
        }
        if(dexCount==0)throw RuntimeFailure("INVALID_JAR")
    }
    fun verify(bytes: ByteArray): List<ByteArray> {
        validate(bytes)
        val dex=mutableListOf<ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                if(entry.name.matches(Regex("classes(\\d+)?\\.dex"))) dex+=ArtifactRepository.readBounded(zip,32*1024*1024)
                else zip.closeEntry()
            }
        }
        return dex
    }
}
class ClassLoaderAdapter {
    fun load(bytes: ByteArray, parent: ClassLoader): ClassLoader {
        val dex = JarVerifier().verify(bytes)
        return if (Build.VERSION.SDK_INT >= 27) InMemoryDexClassLoader(dex.map { ByteBuffer.wrap(it) }.toTypedArray(), parent)
        else dex.fold(parent) { loader, content -> InMemoryDexClassLoader(ByteBuffer.wrap(content), loader) }
    }
}
/** The OS-isolated UID owns this object. No application Context is supplied to third-party code. */
class SpiderRuntime(private val parent: ClassLoader) {
    private var identity = ""
    private var spider: Any? = null
    fun execute(bytes: ByteArray, specification: JsonObject): String {
        val className = SpiderClassResolver().resolve(specification.text("api"))
        val key = RuntimePolicy.hash(bytes) + className + specification.text("sourceId")
        if (identity != key) {
            val loader = ClassLoaderAdapter().load(bytes,parent)
            val type = loader.loadClass(className).asSubclass(com.github.catvod.crawler.Spider::class.java)
            require(type.name.startsWith("com.github.catvod.spider."))
            if(specification.text("operation")=="inspect")return JsonObject().apply{addProperty("class",type.name);addProperty("loaded",true)}.toString()
            spider = type.getDeclaredConstructor().newInstance().also { it.siteKey=specification.text("sourceId") }
            // Compatibility APIs that insist on unrestricted Context are deliberately not supported.
            runCatching { type.getMethod("init", android.content.Context::class.java, String::class.java) }.getOrNull()?.invoke(spider, null, specification.text("ext"))
            identity = key
        }
        val current = spider ?: throw RuntimeFailure("UNSUPPORTED_CLASS")
        val args = specification.array("args")
        fun a(i: Int) = args.toList().getOrNull(i)?.let { if(it.isJsonPrimitive)it.asString else it.toString() }.orEmpty()
        val (method, values) = when(specification.text("operation")) {
            "home" -> "homeContent" to arrayOf<Any>(true)
            "homeVod" -> "homeVideoContent" to emptyArray()
            "category" -> "categoryContent" to arrayOf<Any>(a(0),a(1),true,hashMapOf<String,String>())
            "search" -> "searchContent" to arrayOf<Any>(a(0),false)
            "detail" -> "detailContent" to arrayOf<Any>(arrayListOf(a(0)))
            "play" -> "playerContent" to arrayOf<Any>(a(0),a(1),arrayListOf<String>())
            else -> throw RuntimeFailure("UNSUPPORTED_OPERATION")
        }
        val candidate = current.javaClass.methods.firstOrNull { it.name==method && it.parameterTypes.size==values.size } ?: throw RuntimeFailure("UNSUPPORTED_METHOD")
        val result = try { candidate.invoke(current,*values) as? String ?: throw RuntimeFailure("INVALID_RESULT") }
        catch(error: InvocationTargetException) { throw RuntimeFailure(if(error.cause is OutOfMemoryError) "MEMORY_LIMIT" else "PLUGIN_FAILED") }
        RuntimePolicy.requireSize(result.toByteArray(),RuntimePolicy.RESULT_BYTES)
        return result
    }
}
