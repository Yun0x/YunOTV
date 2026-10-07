package com.yuno.tv

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.yuno.tv.core.model.*
import com.yuno.tv.core.database.ResolvePlaybackUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class YunoEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as YunoApplication
    private val device = UiDevice.getInstance(instrumentation)
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.refresh()
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun text(value: String, timeout: Long = 15_000): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            nodes().lastOrNull { it.text?.toString()?.lineSequence()?.any { line -> line == value } == true && it.isVisibleToUser }?.let { return it }
            Thread.sleep(100)
        }
        screenshot("failure")
        error("Missing UI text: $value")
    }
    private fun click(value: String) {
        val bounds = Rect().also { text(value).getBoundsInScreen(it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
        Thread.sleep(200)
        device.waitForIdle()
    }

    private fun key(code: Int) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
        Thread.sleep(200) // shell injection completes before Compose focus dispatch
        device.waitForIdle()
    }

    private fun screenshot(name: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/yuno-qa-$name.png")).use { it.readBytes() }
    }

    @Test fun configCmsPlaybackHistoryFavoritesAndIptv() = runBlocking {
        val c = app.container
        if (android.os.Build.VERSION.SDK_INT >= 37) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        android.util.Log.i("YunoQA", "initialize")
        withTimeout(15_000) { c.configs.ready.first { it } }
        c.configs.allSources().filter { it.name.startsWith("QA ") }.forEach { c.configs.delete(it.id) }
        // Default remote integration is verified separately; isolate this lawful local-media fixture.
        c.configs.allSources().filter { it.id == "default-bundle" }.forEach { c.configs.update(it.copy(enabled = false)) }
        c.settings.update { it.copy(homeCategory = "continue", lastDestination = "HOME") }
        c.library.clearHistory()
        // Repeated runs retain Room data: remove only this fixture's previous favorites.
        c.library.favorites.first().filter { it.vod.name.startsWith("Yuno Test ") }
            .forEach { c.library.toggleFavorite(it.vod) }
        val json = c.configs.addRemote("QA JSON", "http://10.0.2.2:18765/config.json", "AUTO", 200)
        val txt = c.configs.addRemote("QA TXT", "http://10.0.2.2:18765/sources.txt", "TXT", 10)
        withTimeout(15_000) { c.configs.config.first { it.sites.size == 1 && it.sites[0].origins.size == 2 && it.lives.size == 1 } }
        android.util.Log.i("YunoQA", "config_merged")
        val initial = c.configs.config.value
        val oldLiveFavorites = c.library.liveFavorites.first().toSet()
        initial.lives.filter { it.id in oldLiveFavorites }.forEach { c.library.toggleLiveFavorite(it.id) }
        assertEquals(json, initial.sites.single().origin.sourceId)
        assertEquals(26, app.packageManager.getApplicationInfo(app.packageName, 0).minSdkVersion)
        c.configs.refresh(json) // conditional HTTP 304 must retain the normalized data
        assertEquals(initial.sites.single().id, c.configs.config.value.sites.single().id)
        val source = c.configs.sources.first { rows -> rows.any { it.id == json } }.first { it.id == json }
        c.configs.update(source.copy(enabled = false))
        assertEquals(1, c.configs.config.value.sites.size)
        assertEquals(0, c.configs.config.value.lives.size)
        c.configs.update(source.copy(enabled = true))
        c.configs.update(source.copy(url = "http://10.0.2.2:18765/broken.json"))
        assertEquals(1, c.configs.config.value.sites.size) // failed refresh keeps old cache
        assertEquals(1, c.configs.config.value.lives.size)
        c.configs.update(source)
        c.configs.importLocal("QA local.json", "{\"sites\":[],\"lives\":[]}".toByteArray())
        c.configs.importLocal("QA local.txt", "Local,http://10.0.2.2:18765/cms\r\n".toByteArray())
        assertEquals(1, c.configs.config.value.sites.size)
        assertEquals(3, c.configs.config.value.sites.single().origins.size)
        val result = c.search.search("Yuno", c.configs.config.value.sites)
        assertEquals(3, result.results.size)
        assertTrue(result.errors.isEmpty())
        val vod = c.providers.provider(c.configs.config.value.sites.single()).getDetail("1")
        assertEquals(2, vod.lines.size); assertEquals(2, vod.lines[0].episodes.size)
        android.util.Log.i("YunoQA", "launch_ui")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("最近更新")
        delay(500)
        screenshot("home")
        // A phone emulator may still be in touch mode after installation. The first
        // directional event leaves touch mode without moving the selected item.
        repeat(3) { key(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        repeat(6) { key(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER) // physical remote activates the focused Search control
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/yuno-qa-search-attempt.png").close()
        withTimeout(15_000) { while (nodes().none { it.className?.toString() == "android.widget.EditText" }) delay(100) }
        listOf("Y", "U", "N", "O").forEach(::click); click("搜索")
        text("搜索结果 (3)")
        screenshot("search")
        click("Yuno Test 1")
        text("播放线路与剧集")
        click("收藏")
        withTimeout(10_000) { c.library.favorites.first { items -> items.any { it.vod.id == "1" } } }
        screenshot("detail")
        click("从头播放")
        delay(2500)
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        android.util.Log.i("YunoQA", "verify_playback")
        text("暂停") // Media3 has reached actual playback
        screenshot("player")
        key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        text("播放")
        key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        text("暂停")
        delay(500)
        key(android.view.KeyEvent.KEYCODE_BACK) // controls hidden
        key(android.view.KeyEvent.KEYCODE_BACK) // exit player, persist progress, restore detail
        text("播放线路与剧集")
        val history = withTimeout(10_000) { c.library.history.first { it.isNotEmpty() }.first() }
        assertEquals("1", history.vod.id); assertTrue(history.position > 0); assertTrue(history.duration > 0)
        val resume = ResolvePlaybackUseCase()(history.vod, history)
        assertEquals(history.episodeIndex, resume.episodeIndex)
        assertEquals(history.position, resume.position)
        key(android.view.KeyEvent.KEYCODE_BACK)
        click("历史")
        text("Yuno Test 1 · 第一集")
        click("收藏")
        text("Yuno Test 1")
        click("Yuno Test 1")
        text("播放线路与剧集")
        click("播放") // default play resumes the saved episode
        delay(1800)
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) // ten seconds -> episode end
        text("立即播放")
        click("取消")
        assertTrue(nodes().none { it.text?.toString() == "立即播放" })
        key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) // replay an ended episode
        delay(500)
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        text("立即播放")
        delay(5700) // no action -> automatic next episode
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        text("Yuno Test 1 · 第二集")
        screenshot("next-episode")
        key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        text("播放")
        key(android.view.KeyEvent.KEYCODE_BACK)
        key(android.view.KeyEvent.KEYCODE_BACK)
        text("播放线路与剧集")
        withTimeout(10_000) { c.library.history.first { it.any { record -> record.episodeIndex == 1 } } }
        key(android.view.KeyEvent.KEYCODE_BACK)
        click("直播")
        click("Yuno Live")
        text("全屏播放")
        delay(1500)
        screenshot("live")
        click("收藏")
        withTimeout(10_000) { c.library.liveFavorites.first { it.isNotEmpty() } }
        click("全屏播放")
        delay(1500); key(android.view.KeyEvent.KEYCODE_BACK)
        text("全屏播放")
        click("首页")
        text("继续观看")
        assertTrue(c.library.searches.first().any { it.keyword.equals("Yuno", ignoreCase = true) })
        repeat(5) { key(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        repeat(6) { key(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(3) { key(android.view.KeyEvent.KEYCODE_DPAD_DOWN) } // Hero -> Continue -> Updates
        repeat(3) { key(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        text("播放线路与剧集")
        text("Yuno Test 2")
        key(android.view.KeyEvent.KEYCODE_BACK)
        delay(400)
        screenshot("focus-restored")
        var restored: AccessibilityNodeInfo? = text("Yuno Test 2")
        while (restored != null && !restored.isFocusable) restored = restored.parent
        assertTrue("Returning home must restore the selected film card", restored?.isFocused == true)
        click("搜索")
        click("Yuno Test 3")
        text("播放线路与剧集")
        click("从头播放")
        var errorButton: AccessibilityNodeInfo? = text("重试")
        while (errorButton != null && !errorButton.isFocusable) errorButton = errorButton.parent
        assertTrue("Playback error must focus the retry button", errorButton?.isFocused == true)
        screenshot("playback-error")
        key(android.view.KeyEvent.KEYCODE_DPAD_UP)
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        text("Backup")
        click("Backup")
        delay(2500)
        key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        key(android.view.KeyEvent.KEYCODE_DPAD_UP) // opens hidden controls and resets their idle timer without toggling playback
        text("暂停") // recover from HTTP 404 by selecting the working line
        key(android.view.KeyEvent.KEYCODE_BACK)
        key(android.view.KeyEvent.KEYCODE_BACK)
        text("播放线路与剧集")
        // Leave only test data in this isolated emulator for visual inspection.
        assertTrue(c.configs.sources.value.any { it.id == txt })
    }
}
