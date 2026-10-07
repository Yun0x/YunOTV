package com.yuno.tv.core.runtime

import android.app.Service
import android.content.Intent
import android.os.*
import com.google.gson.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class IsolatedRuntimeService : Service() {
    private val watchdog = Executors.newScheduledThreadPool(2)
    @Volatile private var idleSince = SystemClock.elapsedRealtime()
    override fun onCreate() {
        super.onCreate()
        watchdog.scheduleAtFixedRate({
            val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            val expired=idleSince>0 && SystemClock.elapsedRealtime()-idleSince>30_000
            if(heap>96L*1024*1024 || Thread.getAllStackTraces().size>48 || expired) Process.killProcess(Process.myPid())
        },2,2,TimeUnit.SECONDS)
    }
    protected abstract fun execute(bytes: ByteArray, specification: JsonObject, host: RuntimeHost): String
    override fun onBind(intent: Intent): IBinder = object : IRuntime.Stub() {
        @Synchronized override fun execute(artifact: ParcelFileDescriptor, specification: String, host: IRuntimeHost): String {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            idleSince=0
            val timer = watchdog.schedule({ Process.killProcess(Process.myPid()) },RuntimePolicy.DEADLINE_MS + 1000,TimeUnit.MILLISECONDS)
            try {
                require(specification.length <= 32768)
                val spec = JsonParser.parseString(specification).asJsonObject
                val maximum = if(this@IsolatedRuntimeService is SpiderRuntimeService) RuntimePolicy.JAR_BYTES else 2 * RuntimePolicy.JS_BYTES
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(artifact).use { ArtifactRepository.readBounded(it,maximum) }
                val bridge = object : RuntimeHost {
                    override fun http(url: String, options: String) = host.http(url,options)
                    override fun storage(operation: String, key: String, value: String) = host.storage(operation,key,value)
                }
                val result = execute(bytes,spec,bridge)
                RuntimePolicy.requireSize(result.toByteArray(),RuntimePolicy.RESULT_BYTES)
                return JsonObject().apply { addProperty("result",result) }.toString()
            } catch (error: OutOfMemoryError) { Process.killProcess(Process.myPid()); throw error }
            catch (error: Throwable) {
                android.util.Log.w("YunoRuntime", "runtime_failure:"+error.javaClass.simpleName+":"+
                    error.stackTrace.take(4).joinToString { "${it.className}.${it.methodName}:${it.lineNumber}" })
                val reason = when(error) {
                    is JsDeadline -> "TIMEOUT"
                    is RuntimeFailure -> error.reason
                    is org.mozilla.javascript.EvaluatorException -> "JS_SYNTAX_ERROR"
                    is org.mozilla.javascript.EcmaError -> "JS_${error.name.uppercase()}"
                    is ClassNotFoundException -> "UNSUPPORTED_CLASS"
                    is NoSuchMethodException, is LinkageError -> "UNSUPPORTED_API"
                    else -> "PLUGIN_FAILED"
                }
                return JsonObject().apply { addProperty("error",reason) }.toString()
            } finally { timer.cancel(false);idleSince=SystemClock.elapsedRealtime() }
        }
        override fun abort() { Process.killProcess(Process.myPid()) }
    }
    override fun onDestroy() { watchdog.shutdownNow();super.onDestroy();Process.killProcess(Process.myPid()) }
}
class DrpyRuntimeService : IsolatedRuntimeService() {
    private var runtime: DrpyRuntime? = null
    private var source = ""
    override fun execute(bytes: ByteArray, specification: JsonObject, host: RuntimeHost): String {
        if(source != specification.text("sourceId")) { runtime?.close();runtime=DrpyRuntime(host);source=specification.text("sourceId") }
        return runtime!!.execute(JsonParser.parseString(String(bytes,Charsets.UTF_8)).asJsonObject,specification.text("operation"),specification.array("args"),RuntimePolicy.DEADLINE_MS)
    }
}
class SpiderRuntimeService : IsolatedRuntimeService() {
    private val runtime by lazy { SpiderRuntime(classLoader) }
    override fun execute(bytes: ByteArray, specification: JsonObject, host: RuntimeHost): String = runtime.execute(bytes,specification)
}
