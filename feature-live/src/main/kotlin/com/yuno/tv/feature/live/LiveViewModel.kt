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
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.*

data class LiveUiState(val group: String = "ALL", val selected: UnifiedLive? = null, val error: ErrorCode? = null,
    val playlistId: String = "", val loading: Boolean = false)
@androidx.media3.common.util.UnstableApi
class LiveViewModel(private val configs: ConfigRepository, private val library: LibraryRepository, private val factory: PlayerFactory) : ViewModel() {
    private val playlistChannels = MutableStateFlow<List<UnifiedLive>>(emptyList())
    val config = combine(configs.config, playlistChannels) { base, channels ->
        base.copy(lives = (base.lives + channels).distinctBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnifiedConfig())
    private var playlistJob: Job? = null
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
    fun playlist(id: String) {
        playlistJob?.cancel()
        playlistChannels.value = emptyList()
        _state.update { it.copy(playlistId = id, group = "ALL", loading = true, error = null) }
        playlistJob = viewModelScope.launch {
            try { playlistChannels.value = withTimeout(15_000) { configs.livePlaylist(id) } }
            catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(error = error.friendlyCode()) } }
            finally { _state.update { it.copy(loading = false) } }
        }
    }
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
