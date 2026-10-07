package com.yuno.tv.core.runtime

import android.content.Context
import com.google.gson.*
import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/** Downloads are lazy: importing a bundle never executes or warms all of its plugins. */
class CompatibilityRuntime(context: Context, private val http: OkHttpClient, private val directory: File) {
    val scripts = ArtifactRepository(directory.resolve("js"),http,RuntimePolicy.JS_BYTES,32L*1024*1024) { bytes ->
        val text=com.yuno.tv.core.network.NetworkClient.decodeUtf8(bytes).trimStart()
        if(text.startsWith('<') || text.isBlank())throw RuntimeFailure("INVALID_JS")
    }
    val jars = JarRepository(directory.resolve("jar"),http)
    private val jsWorker = RuntimeIsolation(context,DrpyRuntimeService::class.java)
    private val spiderWorker = RuntimeIsolation(context,SpiderRuntimeService::class.java)
    private val hosts = object : LinkedHashMap<String,RuntimeHttpBridge>(8,0.75f,true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,RuntimeHttpBridge>?) = size>8 }
    private val _diagnostics = MutableStateFlow<Map<String, RuntimeDiagnostic>>(emptyMap())
    val diagnostics = _diagnostics.asStateFlow()
    private val diagnosticMutex=kotlinx.coroutines.sync.Mutex()
    private val diagnosticFile = directory.resolve("evidence.json")
    init { runCatching { require(diagnosticFile.length()<512*1024);val root=JsonParser.parseString(diagnosticFile.readText()).asJsonObject;_diagnostics.value=root.entrySet().take(256).associate { it.key to Gson().fromJson(it.value,RuntimeDiagnostic::class.java) } } }
    fun provider(site: UnifiedSite): SourceProvider = when(site.capability.kind) {
        CapabilityKind.DRPY_JS -> DrpySourceProvider(site,this)
        CapabilityKind.SPIDER_JAR -> SpiderSourceProvider(site,this)
        else -> throw SourceException(ErrorCode.UNSUPPORTED)
    }
    suspend fun call(site: UnifiedSite, operation: String, args: JsonArray): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var hash = ""
        var transfer:File?=null
        try {
            val js = site.capability.kind==CapabilityKind.DRPY_JS
            val artifact = if(js) {
                val engine = scripts.get(site.api);hash=engine.first.hash
                val bundle = JsonObject().apply {
                    addProperty("engine",engine.second.readText())
                    val ext=site.ext.trim(); val rule=if(ext.toHttpUrlOrNull()!=null) scripts.get(ext).second.readText() else ext
                    addProperty("rule",rule)
                    val modules=JsonObject()
                    // Templates are code dependencies, resolved beside the configured engine with the same limits.
                    if(engine.second.readText().contains("模板.js")) runCatching { val url=site.api.toHttpUrlOrNull()!!.resolve("模板.js")!!.toString();modules.addProperty(url,scripts.get(url).second.readText()) }
                    add("modules",modules)
                }
                withContext(Dispatchers.IO) { File.createTempFile("js-",".json",directory.resolve("transfer").apply { mkdirs() }).apply { writeText(bundle.toString());transfer=this } }
            } else jars.get(site.metadata.spider.ifBlank { site.api.substringBefore(';') }).also { hash=it.first.hash }.second
            val spec=JsonObject().apply { addProperty("sourceId",site.id);addProperty("api",site.api);addProperty("ext",site.ext);addProperty("operation",operation);add("args",args) }
            val host = synchronized(hosts) { hosts.getOrPut(site.id) { RuntimeHttpBridge(http,directory.resolve("storage").resolve(RuntimePolicy.hash(site.id.toByteArray())+".json")) } }
            val result=(if(js)jsWorker else spiderWorker).execute(artifact,spec,host)
            record(site,"execute:$operation",true,hash,System.currentTimeMillis()-start,"")
            result
        } catch(error:Exception) {
            error.rethrowCancellation()
            record(site,"execute:$operation",false,hash,System.currentTimeMillis()-start,(error as? RuntimeFailure)?.reason ?: "PLUGIN_FAILED")
            val reason=(error as? RuntimeFailure)?.reason.orEmpty()
            throw SourceException(when {
                reason.contains("TIMEOUT") -> ErrorCode.TIMEOUT
                reason=="DOWNLOAD_FAILED" || error is java.io.IOException -> ErrorCode.NETWORK
                reason=="INVALID_URL" -> ErrorCode.INVALID_URL
                else -> ErrorCode.UNSUPPORTED
            })
        } finally { transfer?.delete() }
    }
    private fun safeUrl(value:String):String=runCatching { val uri=java.net.URI(value);java.net.URI(uri.scheme,null,uri.host,uri.port,uri.path,null,null).toString() }.getOrDefault(value.substringBefore('?').substringBefore('#').replace(Regex("//[^/]*@"),"//"))
    suspend fun validated(site: UnifiedSite, operation: String, success: Boolean) {
        val previous=_diagnostics.value[site.id]
        record(site,operation,success,previous?.hash.orEmpty(),previous?.latencyMs ?: 0,if(success) "" else "INVALID_RESULT")
    }
    private suspend fun record(site: UnifiedSite, operation: String, success: Boolean, hash: String, latency: Long, error: String) {
        _diagnostics.update { current ->
            val old = current[site.id] ?: RuntimeDiagnostic(site.id,site.capability.kind.name,if(site.capability.kind==CapabilityKind.DRPY_JS)"Rhino 1.7.15" else "InMemoryDex/API26",safeUrl(site.api),safeUrl(site.metadata.spider.substringBefore(';')))
            (current + (site.id to old.copy(hash=hash,operations=old.operations + (operation to success),latencyMs=latency,lastError=error))).entries.toList().takeLast(256).associate { it.toPair() }
        }
        diagnosticMutex.withLock { withContext(Dispatchers.IO) { runCatching { directory.mkdirs();val tmp=directory.resolve("evidence.tmp");tmp.writeText(Gson().toJson(_diagnostics.value));tmp.renameTo(diagnosticFile) } } }
    }
    suspend fun clear() { jsWorker.reset();spiderWorker.reset();scripts.clear();jars.clear();withContext(Dispatchers.IO) { directory.resolve("transfer").deleteRecursively();directory.resolve("storage").deleteRecursively() };synchronized(hosts){hosts.clear()} }
}
data class RuntimeDiagnostic(val sourceId: String,val providerType: String,val runtime: String,val api: String,val jar: String,val hash: String="",val operations: Map<String,Boolean> = emptyMap(),val latencyMs: Long=0,val lastError: String="") {
    val fullySupported get() = listOf("home","search","detail","play").all { operations[it]==true }
}
abstract class RuntimeSourceProvider(protected val site: UnifiedSite,private val runtime: CompatibilityRuntime) : SourceProvider {
    private val parser=SpiderResultParser(site)
    private suspend fun call(name:String,vararg args:Any):String = runtime.call(site,name,Gson().toJsonTree(args).asJsonArray)
    override suspend fun getHome():HomeResult {
        val home=parser.home(call("home",true))
        val videos=runCatching { parser.items(call("homeVod")) }.getOrElse { it.rethrowCancellation();emptyList() }
        val result=home.copy(recent=(home.recent+videos).distinctBy { it.identity })
        runtime.validated(site,"home",result.recent.isNotEmpty() || result.categories.isNotEmpty())
        if(result.recent.isEmpty() && result.categories.isEmpty())throw SourceException(ErrorCode.EMPTY)
        return result
    }
    override suspend fun getCategories()=getHome().categories
    override suspend fun getCategory(categoryId:String,page:Int)=parser.items(call("category",categoryId,page,true,emptyMap<String,String>()))
    override suspend fun search(keyword:String)=parser.items(call("search",keyword,false,1)).also{runtime.validated(site,"search",it.isNotEmpty())}
    override suspend fun getDetail(vodId:String)=parser.detail(call("detail",vodId)).also{runtime.validated(site,"detail",it.lines.isNotEmpty())}
    override suspend fun resolvePlayback(episode:UnifiedEpisode,line:UnifiedLine)=parser.play(call("play",line.name,episode.url,emptyList<String>()),PlaybackCandidate(episode.url,headers=site.metadata.headers+episode.headers,source=site.id,line=line.name,episode=episode.name)).also { runtime.validated(site,"play",!it.parseRequired && it.url.toHttpUrlOrNull()!=null) }
}
class DrpySourceProvider(site:UnifiedSite,runtime:CompatibilityRuntime):RuntimeSourceProvider(site,runtime)
class SpiderSourceProvider(site:UnifiedSite,runtime:CompatibilityRuntime):RuntimeSourceProvider(site,runtime)
