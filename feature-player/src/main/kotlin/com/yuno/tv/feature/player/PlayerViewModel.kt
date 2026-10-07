package com.yuno.tv.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.yuno.tv.core.*
import com.yuno.tv.core.config.SettingsRepository
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.player.PlayerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlayerUiState(
    val request: PlaybackRequest, val position: Long = 0L, val duration: Long = 0L,
    val playing: Boolean = false, val buffering: Boolean = true, val error: ErrorCode? = null,
    val countdown: Int? = null, val settings: PlayerSettings = PlayerSettings(), val speed: Float = 1f,
    val aspect: String = "FIT", val tracks: Tracks = Tracks.EMPTY
)
@androidx.media3.common.util.UnstableApi
class PlayerViewModel(
    initial: PlaybackRequest, private val factory: PlayerFactory, private val library: LibraryRepository,
    private val preferences: SettingsRepository, private val appScope: CoroutineScope, private val logger: AppLogger
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState(initial))
    val state = _state.asStateFlow()
    var player: ExoPlayer? by androidx.compose.runtime.mutableStateOf<ExoPlayer?>(null)
        private set
    private var countdown: Job? = null
    private var resumeWhenActive = true
    private var initialized = false
    private var softwareFallback = false
    private val listener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _state.update { it.copy(playing = isPlaying) } }
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) }
            if (playbackState == Player.STATE_ENDED) { save(); startCountdown() }
        }
        override fun onPlayerError(error: PlaybackException) {
            logger.event(LogCategory.PLAYER, "playback_failed", error)
            if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && !softwareFallback) {
                softwareFallback = true
                val request = _state.value.request.copy(position = player?.currentPosition?.coerceAtLeast(0) ?: 0)
                viewModelScope.launch {
                    player?.removeListener(this@PlayerViewModel.listener)
                    player?.release()
                    player = factory.create(_state.value.settings, softwareDecoders = true).also {
                        it.addListener(this@PlayerViewModel.listener)
                        it.setPlaybackSpeed(_state.value.speed)
                    }
                    prepare(request)
                }
                return
            }
            _state.update { it.copy(error = if (error.errorCode in 2000..2999) ErrorCode.NETWORK else ErrorCode.PLAYBACK, buffering = false) }
        }
        override fun onTracksChanged(tracks: Tracks) { _state.update { it.copy(tracks = tracks) } }
    }
    init {
        viewModelScope.launch {
            val settings = preferences.settings.first()
            _state.update { it.copy(settings = settings, speed = settings.speed, aspect = settings.aspect) }
            player = factory.create(settings).also { it.addListener(listener) }
            initialized = true; prepare(initial)
            launch { preferences.settings.collect { value -> _state.update { it.copy(settings = value) } } }
            var ticks = 0
            while (isActive) {
                delay(1000)
                player?.let { current ->
                    _state.update { it.copy(position = current.currentPosition.coerceAtLeast(0), duration = current.duration.coerceAtLeast(0)) }
                    if (++ticks % 7 == 0) save()
                }
            }
        }
    }
    fun prepare(request: PlaybackRequest) {
        cancelNext()
        _state.update { it.copy(request = request, error = null, position = request.position, buffering = true) }
        val episode = request.episode
        if (episode == null || !episode.url.startsWith("http", ignoreCase = true)) {
            _state.update { it.copy(error = ErrorCode.INVALID_URL, buffering = false) }; return
        }
        try {
            val playback = com.yuno.tv.core.network.PlaybackResolver().resolve(episode)
            player?.apply { setMediaSource(factory.mediaSource(playback)); prepare(); seekTo(request.position); playWhenReady = resumeWhenActive }
        } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(error = error.friendlyCode(), buffering = false) } }
    }
    fun retry() { prepare(_state.value.request.copy(position = player?.currentPosition?.coerceAtLeast(0) ?: 0L)) }
    fun play() { player?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun togglePlay() { player?.let { if (it.isPlaying) it.pause() else play() } }
    fun seek(delta: Long) { player?.let { p ->
        val target = (p.currentPosition + delta).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration) else target)
    } }
    fun seekTo(position: Long) { player?.seekTo(position.coerceAtLeast(0)) }
    fun speed(value: Float) { player?.setPlaybackSpeed(value); _state.update { it.copy(speed = value) } }
    fun aspect(value: String) { _state.update { it.copy(aspect = value) } }
    fun episode(index: Int) {
        val current = _state.value.request
        if (current.vod.lines.getOrNull(current.lineIndex)?.episodes?.getOrNull(index) != null) {
            save(); prepare(current.copy(episodeIndex = index, position = 0))
        }
    }
    fun line(index: Int) {
        val current = _state.value.request; val episodes = current.vod.lines.getOrNull(index)?.episodes ?: return
        if (episodes.isEmpty()) return
        save(); prepare(current.copy(lineIndex = index, episodeIndex = current.episodeIndex.coerceAtMost(episodes.lastIndex),
            position = player?.currentPosition?.coerceAtLeast(0) ?: 0))
    }
    fun track(group: Tracks.Group, index: Int) {
        val type = group.type
        player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build() }
    }
    fun subtitlesOff() { player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() } }
    fun save() {
        val p = player ?: return
        val state = _state.value
        if (!state.settings.historyEnabled || state.request.live) return
        val request = state.request; val position = p.currentPosition.coerceAtLeast(0); val duration = p.duration.coerceAtLeast(0)
        val timestamp = System.currentTimeMillis()
        appScope.launch { try { library.saveHistory(request, position, duration, timestamp) }
            catch (error: Exception) { error.rethrowCancellation(); logger.event(LogCategory.DATABASE, "history_save_failed", error) } }
    }
    fun background() {
        resumeWhenActive = player?.playWhenReady ?: true
        save(); cancelNext(); player?.pause()
    }
    fun foreground() { if (initialized && resumeWhenActive) player?.play() }
    fun cancelNext() { countdown?.cancel(); countdown = null; _state.update { it.copy(countdown = null) } }
    private fun startCountdown() {
        val current = _state.value
        if (!current.settings.autoNext || current.request.live) return
        val episodes = current.request.vod.lines.getOrNull(current.request.lineIndex)?.episodes.orEmpty()
        if (current.request.episodeIndex >= episodes.lastIndex) return
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (i in 5 downTo 1) { _state.update { it.copy(countdown = i) }; delay(1000) }
            episode(current.request.episodeIndex + 1)
        }
    }
    override fun onCleared() {
        save(); player?.removeListener(listener); player?.release(); player = null
    }
}
