package com.yuno.tv

import android.content.Intent
import android.content.ComponentName
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/** SDK-only D-pad acceptance can run against the actual optimized Release APK. */
@RunWith(AndroidJUnit4::class)
class ReleaseDailyUseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
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
    /** Routes all activation through physical D-pad keys; coordinates only guide navigation. */
    private fun click(value: String) {
        val visits=mutableMapOf<String,Int>()
        repeat(80) {
            val target=text(value)
            var owner:AccessibilityNodeInfo?=target
            while(owner!=null && !owner.isFocusable)owner=owner.parent
            val focused=nodes().lastOrNull { it.isFocused && it.isVisibleToUser }
            val destination=Rect().also { target.getBoundsInScreen(it) }
            val current=Rect().also { focused?.getBoundsInScreen(it) }
            if(focused!=null && current.contains(destination.centerX(),destination.centerY()) && current.width()*current.height()<device.displayWidth*device.displayHeight*0.8) { key(android.view.KeyEvent.KEYCODE_DPAD_CENTER);return }
            val stateKey="${focused?.text}:${current}"
            if(it%10==0)android.util.Log.i("YunoRemoteQA","target_$value focus_${focused?.text} bounds_$current")
            val tries=visits.getOrDefault(stateKey,0);visits[stateKey]=tries+1
            val dx=destination.centerX()-current.centerX();val dy=destination.centerY()-current.centerY()
            val direction=when {
                tries>1 -> listOf(19,20,21,22)[tries%4]
                focused==null -> 20
                kotlin.math.abs(dy)>current.height()/2+15 -> if(dy>0)20 else 19
                dx>0 -> 22
                dx<0 -> 21
                else -> 20
            }
            key(direction)
        }
        screenshot("failure");error("D-pad could not reach $value")
    }

    private fun key(code: Int) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
        Thread.sleep(200) // shell injection completes before Compose focus dispatch
        device.waitForIdle()
    }

    private fun screenshot(name: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/yuno-qa-$name.png")).use { it.readBytes() }
    }
    private fun returnToDetail() {
        repeat(3) {
            if(nodes().any { it.isVisibleToUser && it.text?.toString()=="播放线路与剧集" })return
            key(4)
        }
        text("播放线路与剧集")
    }

    @Test fun installedReleaseUsesOnlyRemoteKeys() {
        val context=instrumentation.targetContext
        context.startActivity(Intent().setComponent(ComponentName(context.packageName,"com.yuno.tv.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("首页")
        click("搜索")
        listOf("Y","U","N","O").forEach(::click)
        click("搜索");text("搜索结果 (3)");click("Yuno Test 1")
        text("播放线路与剧集")
        if(nodes().any { it.text?.toString()=="取消收藏" }) { click("取消收藏");text("收藏") }
        click("收藏");text("取消收藏")
        click("从头播放");Thread.sleep(1800);key(23);text("暂停")
        key(85);text("播放");key(85);text("暂停")
        key(89);returnToDetail()
        key(4)
        var restored:AccessibilityNodeInfo?=text("Yuno Test 1")
        while(restored!=null && !restored.isFocusable)restored=restored.parent
        assertTrue("Release must restore the selected search card",restored?.isFocused==true)
        click("历史");text("Yuno Test 1 · 第一集")
        click("收藏");click("Yuno Test 1");text("播放线路与剧集")
        click("播放");Thread.sleep(1500);key(23);text("暂停") // Default action resumes the saved episode.
        returnToDetail();key(4)
        click("直播");click("Yuno Live");text("全屏播放")
        click("全屏播放");Thread.sleep(1500);key(4);text("全屏播放")
        click("设置");text("配置源管理");screenshot("release-settings")
    }
}
