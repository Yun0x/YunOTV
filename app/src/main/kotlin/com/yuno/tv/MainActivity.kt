package com.yuno.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuno.tv.core.model.PlayerSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yuno.tv.core.ui.YunoTheme

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val settings by (application as YunoApplication).container.settings.settings.collectAsStateWithLifecycle(PlayerSettings())
            YunoTheme(settings.theme) { YunoApp() }
        }
    }
}
