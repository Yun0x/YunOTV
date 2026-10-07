package com.yuno.tv.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.yuno.tv.core.model.PlayerSettings
import okhttp3.OkHttpClient

@androidx.media3.common.util.UnstableApi
class PlayerFactory(private val context: Context, private val http: OkHttpClient) {
    // A movie stream can remain open for hours. Source/download deadlines must not cap playback length.
    private val mediaHttp = http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).cache(null).build()
    fun mediaSource(playback: com.yuno.tv.core.model.ResolvedPlayback): androidx.media3.exoplayer.source.MediaSource {
        val data = OkHttpDataSource.Factory(mediaHttp).setDefaultRequestProperties(playback.headers).setUserAgent(playback.userAgent)
        val item = androidx.media3.common.MediaItem.Builder().setUri(playback.url)
            .apply { if (playback.contentType.isNotBlank()) setMimeType(playback.contentType) }.build()
        return DefaultMediaSourceFactory(data).createMediaSource(item)
    }
    fun create(settings: PlayerSettings, softwareDecoders: Boolean = false): ExoPlayer = ExoPlayer.Builder(context.applicationContext,
        DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true).apply {
            if (softwareDecoders) setMediaCodecSelector { mimeType, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    .filter { it.softwareOnly }
            }
        })
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(mediaHttp)))
        .setSeekBackIncrementMs(settings.seekSeconds * 1000L).setSeekForwardIncrementMs(settings.seekSeconds * 1000L)
        .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
        .setHandleAudioBecomingNoisy(true).build().apply { setPlaybackSpeed(settings.speed) }
}
