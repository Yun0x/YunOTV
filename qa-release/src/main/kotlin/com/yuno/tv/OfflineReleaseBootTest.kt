package com.yuno.tv

import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SDK-only acceptance against the signed, R8-minified Release APK. No third-party video playback. */
@RunWith(AndroidJUnit4::class)
class OfflineReleaseBootTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val connections = mutableListOf<ServiceConnection>()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun call(service: String, file: File, api: String, operation: String = "inspect"): JSONObject {
        val ready = CountDownLatch(1); var endpoint: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { endpoint = binder; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName, "com.yuno.tv.core.runtime.$service")), connection, Context.BIND_AUTO_CREATE))
        connections += connection
        assertTrue(ready.await(8, TimeUnit.SECONDS))
        val host = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.yuno.tv.core.runtime.IRuntimeHost"); return true }
                data.enforceInterface("com.yuno.tv.core.runtime.IRuntimeHost")
                reply?.writeNoException(); reply?.writeString(if (code == 1) "{\"code\":503,\"content\":\"\",\"headers\":{}}" else "")
                return true
            }
        }
        val spec = JSONObject().put("sourceId", "offline-release").put("api", api).put("ext", "").put("operation", operation).put("args", JSONArray())
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken("com.yuno.tv.core.runtime.IRuntime")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                request.writeInt(1); fd.writeToParcel(request, 0); request.writeString(spec.toString()); request.writeStrongBinder(host)
                assertTrue(endpoint!!.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
            }
            reply.readException()
            return JSONObject(reply.readString()!!)
        } finally { request.recycle(); reply.recycle() }
    }
    @Test fun githubBlockedFreshBootLocalBundleJsAndJar() {
        val github = java.net.URL("https://raw.githubusercontent.com/").openConnection() as java.net.HttpURLConnection
        github.connectTimeout = 3000; github.readTimeout = 3000
        try { assertFalse("GitHub proxy must reject requests", github.responseCode in 200..299) }
        catch (_: java.io.IOException) {} finally { github.disconnect() }
        val bytes = context.assets.open("yunotv/yunotv-bundle.json").use { it.readBytes() }
        val bundle = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("yunotv-compiled-bundle", bundle.getString("format"))
        val config = bundle.getJSONObject("config")
        val sites = config.getJSONArray("sites")
        assertTrue(sites.length() > 0)
        val index = JSONObject(context.assets.open("yunotv/resource-index.json").bufferedReader().use { it.readText() })
        assertEquals(sha(bytes), index.getString("bundleSha256"))
        val resources = index.getJSONObject("resources")
        val playlistUrl = resources.keys().asSequence().first { resources.getJSONObject(it).getString("type") == "PLAYLIST" }
        val playlist = resources.getJSONObject(playlistUrl)
        val playlistData = java.util.zip.GZIPInputStream(context.assets.open(playlist.getString("asset"))).use { it.readBytes() }
        assertEquals(playlist.getString("sha256"), sha(playlistData))
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("首页")), 15000))
        // Fresh boot actually inserted a local compiled source, without obtaining a remote manifest.
        val dbFile = context.getDatabasePath("yuno-tv.db")
        var inserted = false
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!inserted && SystemClock.elapsedRealtime() < deadline) {
            if (dbFile.isFile) runCatching {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT format,local FROM sources WHERE id='default-bundle'", null).use { row ->
                        inserted = row.moveToFirst() && row.getString(0) == "COMPILED" && row.getInt(1) == 1
                    }
                }
            }
            if (!inserted) SystemClock.sleep(200)
        }
        assertTrue("Local compiled bundle imported at fresh boot", inserted)
        val directory = context.filesDir.resolve("offline-release-qa").apply { mkdirs() }
        try {
            var jsWorking = false; var jarWorking = false; var cmsPresent = false
            val triedJs = mutableSetOf<String>(); val triedJar = mutableSetOf<String>()
            for (i in 0 until sites.length()) {
                val site = sites.getJSONObject(i)
                val kind = site.getJSONObject("capability").getString("kind")
                val api = site.getString("api")
                if (kind == "CMS_API") cmsPresent = true
                if (!jsWorking && kind == "DRPY_JS" && api.contains("drpy", true) && resources.has(api) && triedJs.size < 8 && triedJs.add(api)) {
                    val entry = resources.getJSONObject(api)
                    val data = context.assets.open(entry.getString("asset")).use { it.readBytes() }
                    assertEquals(entry.getString("sha256"), sha(data))
                    val script = JSONObject().put("engine", String(data, Charsets.UTF_8)).put("modules", JSONObject())
                        .put("rule", "var rule={title:'Yuno Original Offline QA',host:'https://example.invalid',url:'/list/fyclass',class_name:'Original',class_url:'original',一级:[],二级:'*',搜索:[]};")
                    val file = directory.resolve("engine.json").apply { writeText(script.toString()) }
                    val result = call("DrpyRuntimeService", file, api)
                    jsWorking = !result.has("error") && result.optString("result").contains("initialized")
                }
                val jarUrl = site.getJSONObject("metadata").getString("spider").substringBefore(";md5;")
                if (!jarWorking && kind == "SPIDER_JAR" && resources.has(jarUrl) && api.startsWith("csp_") && triedJar.size < 20 && triedJar.add(jarUrl + api)) {
                    val entry = resources.getJSONObject(jarUrl)
                    val data = context.assets.open(entry.getString("asset")).use { it.readBytes() }
                    assertEquals(entry.getString("sha256"), sha(data))
                    val file = directory.resolve("plugin.jar").apply { writeBytes(data) }
                    val result = call("SpiderRuntimeService", file, api)
                    jarWorking = !result.has("error") && result.optString("result").contains("loaded")
                }
                if (jsWorking && jarWorking && cmsPresent) break
            }
            assertTrue("CMS source present", cmsPresent)
            assertTrue("Actual bundled JS initialized", jsWorking)
            assertTrue("Actual bundled JAR/class loaded", jarWorking)
            // Navigate solely with the TV remote to the source management page.
            device.pressDPadUp(); repeat(5) { device.pressDPadRight() }; device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("配置源管理")), 8000))
            device.pressDPadDown(); device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.textContains("Yuno TV Source Bundle")), 8000))
            device.takeScreenshot(context.filesDir.resolve("offline-release-qa.png"))
        } finally {
            connections.forEach { runCatching { context.unbindService(it) } }; connections.clear(); directory.deleteRecursively()
        }
    }
}
