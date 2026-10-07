package com.yuno.tv
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import com.yuno.tv.core.player.PlayerFactory
import com.yuno.tv.core.model.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.*
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class LegalPlaybackTest {
    @Test fun originalMp4AndHlsDecodeFirstFrame() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for((url,type) in listOf("http://10.0.2.2:18765/clip.mp4" to "video/mp4","http://10.0.2.2:18766/playlist.m3u8" to "application/x-mpegURL")) {
            val frame=CountDownLatch(1);var failed=false
            var player:androidx.media3.exoplayer.ExoPlayer?=null;var surface:Surface?=null;var texture:SurfaceTexture?=null
            instrumentation.runOnMainSync {
                val factory=PlayerFactory(instrumentation.targetContext, okhttp3.OkHttpClient())
                texture=SurfaceTexture(false);surface=Surface(texture)
                player=factory.create(PlayerSettings()).apply {
                    volume=0f;setVideoSurface(surface)
                    addListener(object:Player.Listener {
                        override fun onRenderedFirstFrame(){frame.countDown()}
                        override fun onPlayerError(error:PlaybackException){failed=true;frame.countDown()}
                    })
                    setMediaSource(factory.mediaSource(ResolvedPlayback(url,contentType=type)));prepare();play()
                }
            }
            try { assertTrue("First frame deadline",frame.await(15,TimeUnit.SECONDS));assertFalse("Media3 failed lawful fixture",failed) }
            finally { instrumentation.runOnMainSync { player?.release();surface?.release();texture?.release() } }
        }
    }
}
