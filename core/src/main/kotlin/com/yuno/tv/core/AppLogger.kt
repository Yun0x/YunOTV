package com.yuno.tv.core

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

enum class LogCategory { CONFIG, NETWORK, PLAYER, DATABASE, SOURCE }

/** Never log URL, body, user input, credentials, exception messages or stack traces. */
class AppLogger(context: Context, private val debug: Boolean) {
    private val directory = File(context.filesDir, "diagnostics")
    private val file = File(directory, "events.log")
    private val writer = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128), { task -> Thread(task, "yuno-diagnostics").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    fun event(category: LogCategory, event: String, error: Throwable? = null) {
        val safeEvent = event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val line = "${Instant.now()} ${category.name} $safeEvent ${error?.javaClass?.simpleName.orEmpty()}\n"
        if (debug) Log.d("YunoTV/${category.name}", line.trim())
        if (debug || error != null) {
            writer.execute { runCatching {
                directory.mkdirs()
                if (file.length() > 256 * 1024) {
                    file.copyTo(File(directory, "previous.log"), overwrite = true)
                    file.writeText("")
                }
                file.appendText(line)
            } }
        }
    }
    fun export(): ByteArray {
        runCatching { writer.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        return (runCatching { File(directory, "previous.log").readText() }.getOrDefault("") +
        runCatching { file.readText() }.getOrDefault("")).toByteArray(Charsets.UTF_8)
    }
}
