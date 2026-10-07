package com.yuno.tv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R
import kotlinx.coroutines.delay

@androidx.media3.common.util.UnstableApi
@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var activityTick by remember { mutableIntStateOf(0) }
    val controlFocus = remember { FocusRequester() }
    val root = remember { FocusRequester() }
    val countdownFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    // A dialog's former target may disappear after switching an errored line.
    // Restore the currently visible surface after its focus nodes attach.
    LaunchedEffect(menu, state.error, state.countdown != null, controls) {
        if (menu == null) {
            withFrameNanos { }
            when {
                state.countdown != null -> countdownFocus
                state.error != null -> errorFocus
                controls -> controlFocus
                else -> root
            }.requestFocus()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.background()
            else if (event == Lifecycle.Event.ON_START) vm.foreground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.save() }
    }
    LaunchedEffect(controls, activityTick, state.playing, menu) {
        if (controls && state.playing && menu == null) { delay(8_000); controls = false; root.requestFocus() }
    }
    val handleBack: () -> Unit = {
        when { menu != null -> menu = null; controls -> { controls = false; root.requestFocus() }; else -> { vm.save(); onExit() } }
    }
    BackHandler(onBack = handleBack)
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(root).pointerInput(state.error, state.countdown) {
        detectTapGestures { if (state.error == null && state.countdown == null && menu == null) controls = !controls }
    }.onPreviewKeyEvent { event ->
        // Consume both halves of the remote Back event. API 26's legacy Activity
        // dispatch can otherwise receive KeyUp after a focused control handles KeyDown.
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.type == KeyEventType.KeyDown) handleBack()
            return@onPreviewKeyEvent true
        }
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        activityTick++
        if (state.error != null && event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return@onPreviewKeyEvent false
        if (state.countdown != null && event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { vm.togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { vm.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player?.pause(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seek(state.settings.seekSeconds * 1000L); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seek(-state.settings.seekSeconds * 1000L); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!controls && menu == null) { vm.seek(-state.settings.seekSeconds * 1000L); true } else false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls && menu == null) { vm.seek(state.settings.seekSeconds * 1000L); true } else false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls && menu == null) { controls = true; true } else false
            KeyEvent.KEYCODE_DPAD_UP -> if (!controls && menu == null) { controls = true; true } else false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls && menu == null) { menu = "episodes"; true } else false
            else -> false
        }
    }.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
            controls = !controls; true
        } else false
    }.focusable()) {
        val player = vm.player
        if (player != null) AndroidView(factory = { context -> PlayerView(context).apply { this.player = player; useController = false; isFocusable = false } },
            update = { view ->
                view.player = player
                view.resizeMode = when (state.aspect) { "FILL" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; "CROP" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "16:9" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; "ORIGINAL" -> AspectRatioFrameLayout.RESIZE_MODE_FIT; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
            }, modifier = if (state.aspect == "16:9") Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxSize())
        if (state.buffering && state.error == null) Text(stringResource(if(state.tryingBackup)R.string.trying_backup else R.string.buffering), modifier = Modifier.align(Alignment.Center))
        if (state.error != null) Column(Modifier.align(Alignment.Center).background(CinemaColors.panel).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(errorText(state.error!!)); Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.retry), Modifier.focusRequester(errorFocus), onClick = vm::retry)
                TvAction(stringResource(R.string.switch_line)) { menu = "lines" }
                TvAction(stringResource(R.string.back)) { vm.save(); onExit() }
            }
        }
        if (controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(YunoColors.BackgroundSecondary.copy(alpha = .94f)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.request.vod.name + " · " + state.request.episode?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            Text("${formatTime(state.position)} / ${formatTime(state.duration)}", color = CinemaColors.muted)
            if (!state.request.live && state.duration > 0) Slider(value = (state.position.toFloat() / state.duration).coerceIn(0f, 1f),
                onValueChange = { vm.seekTo((it * state.duration).toLong()) }, modifier = Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { vm.seek(-state.settings.seekSeconds * 1000L); true }
                    else if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { vm.seek(state.settings.seekSeconds * 1000L); true }
                    else false
                })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                item { TvAction(stringResource(if (state.playing) R.string.pause else R.string.play), Modifier.focusRequester(controlFocus), onClick = vm::togglePlay) }
                item { TvAction(stringResource(R.string.seek_back, state.settings.seekSeconds)) { vm.seek(-state.settings.seekSeconds * 1000L) } }
                item { TvAction(stringResource(R.string.seek_forward, state.settings.seekSeconds)) { vm.seek(state.settings.seekSeconds * 1000L) } }
                if (!state.request.live) {
                    item { TvAction(stringResource(R.string.previous_episode)) { vm.episode(state.request.episodeIndex - 1) } }
                    item { TvAction(stringResource(R.string.next_episode)) { vm.episode(state.request.episodeIndex + 1) } }
                    item { TvAction(stringResource(R.string.episodes)) { menu = "episodes" } }
                    item { TvAction(stringResource(R.string.speed_value, state.speed)) { menu = "speed" } }
                    item { TvAction(stringResource(R.string.switch_line)) { menu = "lines" } }
                }
                item { TvAction(stringResource(R.string.aspect)) { menu = "aspect" } }
                item { TvAction(stringResource(R.string.subtitles)) { menu = "subtitles" } }
                item { TvAction(stringResource(R.string.audio_tracks)) { menu = "audio" } }
                item { TvAction(stringResource(R.string.hide_controls)) { controls = false; root.requestFocus() } }
            }
        }
        state.countdown?.let { count -> Column(Modifier.align(Alignment.Center).background(CinemaColors.panel).padding(24.dp)) {
            Text(stringResource(R.string.next_countdown, count))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.play_now), Modifier.focusRequester(countdownFocus)) { vm.episode(state.request.episodeIndex + 1) }
                TvAction(stringResource(R.string.cancel), onClick = vm::cancelNext)
            }
        } }
    }
    menu?.let { selected -> AlertDialog(onDismissRequest = { menu = null },
        title = { Text(stringResource(when (selected) { "speed" -> R.string.speed; "aspect" -> R.string.aspect; "lines" -> R.string.play_lines; "subtitles" -> R.string.subtitles; "audio" -> R.string.audio_tracks; else -> R.string.episodes })) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (selected) {
                "speed" -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value -> item { TvAction("${value}×") { vm.speed(value); menu = null } } }
                "aspect" -> listOf("ORIGINAL" to R.string.aspect_original, "16:9" to R.string.aspect_16_9, "FILL" to R.string.aspect_fill, "FIT" to R.string.aspect_fit, "CROP" to R.string.aspect_crop).forEach { (value, label) -> item { TvAction(stringResource(label)) { vm.aspect(value); menu = null } } }
                "lines" -> state.request.vod.lines.forEachIndexed { index, line -> item { TvAction(line.name) { vm.line(index); menu = null } } }
                "episodes" -> state.request.vod.lines.getOrNull(state.request.lineIndex)?.episodes?.forEachIndexed { index, episode -> item { TvAction(episode.name) { vm.episode(index); menu = null } } }
                else -> {
                    val type = if (selected == "subtitles") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
                    if (type == C.TRACK_TYPE_TEXT) item { TvAction(stringResource(R.string.subtitles_off)) { vm.subtitlesOff(); menu = null } }
                    val groups = state.tracks.groups.filter { it.type == type }
                    if (groups.isEmpty()) item { Text(stringResource(R.string.no_tracks)) }
                    groups.forEach { group -> (0 until group.length).forEach { index ->
                        if (group.isTrackSupported(index)) item { val format = group.getTrackFormat(index)
                            TvAction(format.label ?: format.language ?: (index + 1).toString()) { vm.track(group, index); menu = null }
                        }
                    } }
                }
            }
        } }, confirmButton = { TvAction(stringResource(R.string.close)) { menu = null } }) }
}
private fun formatTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}
