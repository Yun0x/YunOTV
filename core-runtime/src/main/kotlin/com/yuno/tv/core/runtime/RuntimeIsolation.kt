package com.yuno.tv.core.runtime

import android.content.*
import android.os.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One worker per runtime, one live engine per worker. Hard timeout belongs to the isolated UID. */
class RuntimeIsolation(private val context: Context, private val serviceClass: Class<out IsolatedRuntimeService>) {
    private val mutex = Mutex()
    private val executor = Executors.newSingleThreadExecutor()
    private var runtime: IRuntime? = null
    private var connection: ServiceConnection? = null
    private suspend fun bind(): IRuntime {
        runtime?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return withTimeout(5000) { suspendCancellableCoroutine { continuation ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val value = IRuntime.Stub.asInterface(binder)
                    runCatching { binder.linkToDeath({ runtime=null },0) }
                    runtime = value
                    if(continuation.isActive) continuation.resume(value)
                }
                override fun onServiceDisconnected(name: ComponentName) { runtime=null }
                override fun onBindingDied(name: ComponentName) { runtime=null;runCatching { context.unbindService(this) };connection=null }
                override fun onNullBinding(name: ComponentName) { if(continuation.isActive)continuation.resumeWithException(RuntimeFailure("RUNTIME_UNAVAILABLE")) }
            }
            connection?.let { runCatching { context.unbindService(it) } }
            connection=conn
            if(!context.bindService(Intent(context,serviceClass),conn,Context.BIND_AUTO_CREATE)) continuation.resumeWithException(RuntimeFailure("RUNTIME_UNAVAILABLE"))
            continuation.invokeOnCancellation { runCatching { context.unbindService(conn) };connection=null }
        } }
    }
    suspend fun execute(artifact: File, specification: JsonObject, host: RuntimeHost): String = mutex.withLock {
        val endpoint = bind()
        val broker = object : IRuntimeHost.Stub() {
            override fun http(url: String, options: String): String = try { host.http(url,options) } catch(_:Exception) { "{\"code\":0,\"content\":\"\",\"headers\":{}}" }
            override fun storage(operation: String, key: String, value: String): String = host.storage(operation,key,value)
        }
        withTimeout(RuntimePolicy.DEADLINE_MS + 3000) { suspendCancellableCoroutine { continuation ->
            executor.execute {
                try {
                    val result = ParcelFileDescriptor.open(artifact,ParcelFileDescriptor.MODE_READ_ONLY).use { endpoint.execute(it,specification.toString(),broker) }
                    val json = JsonParser.parseString(result).asJsonObject
                    val error = json.text("error")
                    if(error.isNotBlank()) throw RuntimeFailure(error)
                    if(continuation.isActive)continuation.resume(json.text("result"))
                } catch (error: Throwable) {
                    if(error is DeadObjectException)runtime=null
                    if(continuation.isActive)continuation.resumeWithException(if(error is DeadObjectException)RuntimeFailure("RUNTIME_TERMINATED")else error)
                }
            }
            continuation.invokeOnCancellation { (host as? RuntimeHttpBridge)?.cancelRequests();runCatching { endpoint.abort() };runtime=null }
        } }
    }
    fun reset() { runCatching { runtime?.abort() };runtime=null;connection?.let { runCatching { context.unbindService(it) } };connection=null }
}
