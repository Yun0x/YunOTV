package com.yuno.tv.feature.live

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.yuno.tv.core.config.ConfigRepository
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.*
import com.yuno.tv.core.player.PlayerFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

data class LiveUiState(val group: String = "ALL", val selected: UnifiedLive? = null, val error: ErrorCode? = null)
@androidx.media3.common.util.UnstableApi
class LiveViewModel(configs: ConfigRepository, private val library: LibraryRepository, private val factory: PlayerFactory) : ViewModel() {
    val config = configs.config
    val favorites = library.liveFavorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent = library.liveRecent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(LiveUiState())
    val state = _state.asStateFlow()
    var player: ExoPlayer? by mutableStateOf<ExoPlayer?>(null)
        private set
    private var previewActive = false
    private var softwareFallback = false
    private val listener: Player.Listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && !softwareFallback) {
                softwareFallback = true
                viewModelScope.launch {
                    if (!previewActive) return@launch
                    player?.removeListener(this@LiveViewModel.listener)
                    player?.release()
                    player = factory.create(PlayerSettings(), softwareDecoders = true).also { it.addListener(this@LiveViewModel.listener) }
                    _state.value.selected?.let(::select)
                }
            } else _state.update { it.copy(error = ErrorCode.PLAYBACK) }
        }
    }
    fun group(value: String) { _state.update { it.copy(group = value) } }
    fun select(channel: UnifiedLive) {
        previewActive = true
        if (player == null) player = factory.create(PlayerSettings(), softwareDecoders = softwareFallback).also { it.addListener(listener) }
        _state.update { it.copy(selected = channel, error = null) }
        try {
            val playback = com.yuno.tv.core.network.PlaybackResolver().resolve(UnifiedEpisode(channel.id, channel.name, channel.url))
            player?.apply { setMediaSource(factory.mediaSource(playback)); prepare(); play() }
        } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(error = error.friendlyCode()) } }
        viewModelScope.launch { library.saveLiveRecent(channel.id) }
    }
    fun favorite(id: String) { viewModelScope.launch { library.toggleLiveFavorite(id) } }
    fun pause() {
        previewActive = false
        player?.removeListener(listener); player?.release(); player = null
    }
    fun resume() {
        previewActive = true
        if (player == null) _state.value.selected?.let(::select) else player?.play()
    }
    fun playback(): PlaybackRequest? = _state.value.selected?.let { channel ->
        PlaybackRequest(UnifiedVod(channel.id, channel.origin.sourceId, channel.origin.sourceName, channel.name, channel.logo,
            lines = listOf(UnifiedLine("live", channel.group, listOf(UnifiedEpisode(channel.id, channel.name, channel.url))))), live = true)
    }
    override fun onCleared() { pause() }
}
