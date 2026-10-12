package com.yuno.tv.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.yuno.tv.core.model.PlaybackRequest
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R

@androidx.media3.common.util.UnstableApi
@Composable
fun LiveScreen(vm: LiveViewModel, onFullscreen: (PlaybackRequest) -> Unit, onAddSource: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.pause() else if (event == Lifecycle.Event.ON_START) vm.resume() }
        owner.lifecycle.addObserver(observer); vm.resume()
        onDispose { owner.lifecycle.removeObserver(observer); vm.pause() }
    }
    if (config.lives.isEmpty() && config.playlists.isEmpty()) {
        EmptyState(stringResource(R.string.no_live), stringResource(R.string.live_hint), stringResource(R.string.add_source), onAddSource); return
    }
    val groups = remember(config.lives) { config.lives.groupBy { it.group }.map { (name, channels) ->
        com.yuno.tv.core.model.UnifiedLiveGroup("GROUP:$name", name, channels)
    } }
    val channels = remember(config.lives, state.group, favorites, recent) {
        when (state.group) {
            "ALL" -> config.lives
            "FAVORITES" -> config.lives.filter { it.id in favorites }
            "RECENT" -> recent.mapNotNull { id -> config.lives.firstOrNull { it.id == id } }
            else -> config.lives.filter { it.group == state.group.removePrefix("GROUP:") }
        }
    }
    val restore = remember { FocusRequester() }
    val selectedIndex = channels.indexOfFirst { it.id == state.selected?.id }.coerceAtLeast(0)
    val channelScroll = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(Unit) { if (state.selected != null && channels.isNotEmpty()) { withFrameNanos { }; restore.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            item { TvAction(stringResource(R.string.all_channels)) { vm.group("ALL") } }
            item { TvAction(stringResource(R.string.live_favorites)) { vm.group("FAVORITES") } }
            item { TvAction(stringResource(R.string.recent_watched)) { vm.group("RECENT") } }
            config.playlists.forEach { playlist -> item(key = "PLAYLIST:${playlist.id}") {
                TvAction(playlist.name, selected = state.playlistId == playlist.id) { vm.playlist(playlist.id) }
            } }
            groups.forEach { group -> item(key = group.id) { TvAction(group.name, selected = state.group == group.id) { vm.group(group.id) } } }
        }
        LazyColumn(Modifier.width(260.dp), state = channelScroll, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            channels.forEach { channel -> item(key = channel.id) {
                TvAction((if (state.selected?.id == channel.id) "▶ " else "") + channel.name, (if (channel.id == channels.getOrNull(selectedIndex)?.id) Modifier.focusRequester(restore) else Modifier).fillMaxWidth(), selected = state.selected?.id == channel.id) { vm.select(channel) }
            } }
            if (channels.isEmpty()) item { Text(stringResource(R.string.no_channels)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) Text(stringResource(R.string.loading))
            if (state.selected == null) state.error?.let { Text(errorText(it)) }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(CinemaColors.panel), contentAlignment = Alignment.Center) {
                val player = vm.player
                if (player != null) AndroidView(factory = { context -> PlayerView(context).apply { this.player = player; useController = false; isFocusable = false } },
                    update = { it.player = player }, modifier = Modifier.fillMaxSize())
                else Text(stringResource(R.string.select_channel))
            }
            state.selected?.let { channel ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channel.logo.isNotBlank()) AsyncImage(rememberYunoImage(channel.logo, 96, 96),
                        channel.name, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp))
                    Text(channel.name + " · " + channel.origin.sourceName)
                }
                if (channel.epg.isNotBlank()) Text(stringResource(R.string.epg_optional), color = CinemaColors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction(stringResource(R.string.fullscreen)) { vm.pause(); vm.playback()?.let(onFullscreen) }
                    TvAction(stringResource(if (channel.id in favorites) R.string.remove_favorite else R.string.add_favorite)) { vm.favorite(channel.id) }
                }
                state.error?.let { Text(errorText(it)); TvAction(stringResource(R.string.retry)) { vm.select(channel) } }
            }
        }
    }
}
