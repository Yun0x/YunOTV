package com.yuno.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yuno.tv.core.AppLogger
import com.yuno.tv.core.LogCategory
import com.yuno.tv.core.config.*
import com.yuno.tv.core.database.*
import com.yuno.tv.core.network.*
import kotlinx.coroutines.*

@androidx.media3.common.util.UnstableApi
class YunoApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Media3 messages may contain a remote URI or server response. Export only safe event IDs.
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) = record("debug", tag, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = record("info", tag, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) = record("warning", tag, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = record("error", tag, throwable)
            private fun record(level: String, tag: String, throwable: Throwable?) {
                container.logger.event(LogCategory.PLAYER, "media3_${level}_$tag", throwable)
            }
        })
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.network.http).memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(48L * 1024 * 1024).build() }
        .respectCacheHeaders(false).build()
}
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val logger = AppLogger(app, BuildConfig.DEBUG)
    private val database = YunoDatabase.create(app)
    val network = NetworkClient(app)
    val library = LibraryRepository(database)
    val settings = SettingsRepository(app)
    val configs = ConfigRepository(SourceStore(database, app.filesDir.resolve("config-cache")), network, settings, scope, logger) { app.assets.open("sources.txt.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    val providers = ProviderRegistry(network, app.filesDir.resolve("media-cache"))
    val search = SearchAggregator(providers)
    init { scope.launch { providers.healthChecker.restore(); configs.initialize() } }
}
