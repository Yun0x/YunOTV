package com.yuno.tv.feature.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yuno.tv.core.database.ResolvePlaybackUseCase
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R

@Composable
fun DetailScreen(vm: DetailViewModel, onPlay: (PlaybackRequest) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val vod = state.vod
    var sourceDialog by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val rememberedEpisode = vm.focusMemory.positions["episodes"]
    val episodes = vod.lines.getOrNull(state.selectedLine)?.episodes.orEmpty()
    val rememberedIndex = episodes.indexOfFirst { it.id == rememberedEpisode }.coerceAtLeast(0)
    val episodeScroll = rememberLazyGridState(initialFirstVisibleItemIndex = rememberedIndex)
    LaunchedEffect(state.loading) {
        if (!state.loading) { withFrameNanos { }; if (vm.focusMemory.activeRow == "episodes" && rememberedEpisode != null && episodes.isNotEmpty()) episodeFocus.requestFocus() else if (vod.lines.isNotEmpty()) playFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
    if (vod.background.isNotBlank()) AsyncImage(rememberYunoImage(vod.background, 1280, 720),
        null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(.15f))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.width(150.dp).height(225.dp).background(CinemaColors.panel), contentAlignment = Alignment.Center) {
                Text(vod.name.take(1), color = CinemaColors.accent, style = MaterialTheme.typography.displayMedium)
                AsyncImage(rememberYunoImage(vod.poster, 360, 540),
                    vod.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(vod.name, style = MaterialTheme.typography.headlineLarge)
                Text(listOf(vod.year, vod.area, vod.type, vod.rating, vod.sourceName).filter(String::isNotBlank).joinToString(" · "), color = CinemaColors.muted)
                if (vod.director.isNotBlank()) Text(stringResource(R.string.director_value, vod.director))
                if (vod.actors.isNotBlank()) Text(stringResource(R.string.actors_value, vod.actors), maxLines = 1)
                Text(vod.description.ifBlank { stringResource(R.string.no_description) }, maxLines = 3, color = CinemaColors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vod.lines.isNotEmpty()) {
                        TvAction(stringResource(R.string.play), detailActionModifier("play", vm.focusMemory, playFocus)) {
                            val h = state.history?.takeIf { it.lineIndex == state.selectedLine }
                            onPlay(if (h == null) PlaybackRequest(vod, state.selectedLine) else ResolvePlaybackUseCase()(vod, h))
                        }
                        TvAction(stringResource(R.string.play_from_start), detailActionModifier("start", vm.focusMemory, playFocus)) { vm.focusMemory.positions["actions"] = "start"; vm.focusMemory.activeRow = "actions"; onPlay(PlaybackRequest(vod, state.selectedLine)) }
                        state.history?.let { h -> TvAction(stringResource(R.string.resume_percent, (h.progress * 100).toInt()), detailActionModifier("resume", vm.focusMemory, playFocus)) {
                            onPlay(ResolvePlaybackUseCase()(vod, h))
                        } }
                    }
                    TvAction(stringResource(if (state.favorite) R.string.remove_favorite else R.string.add_favorite), onClick = vm::toggleFavorite)
                    TvAction(stringResource(R.string.back), onClick = onBack)
                }
            }
        }
        if (state.loading) Text(stringResource(R.string.loading))
        state.error?.let { Text(errorText(it)); TvAction(stringResource(R.string.retry), onClick = vm::load) }
        if (!state.loading && vod.lines.isEmpty()) Text(stringResource(R.string.no_direct_play), color = CinemaColors.muted)
        if (vod.lines.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.play_lines))
                TvAction(stringResource(R.string.choose_source)) { sourceDialog = true; vm.alternatives() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                vod.lines.forEachIndexed { index, line -> item(key = line.id) {
                    TvAction(if (state.selectedLine == index) "✓ ${line.name}" else line.name) { vm.selectLine(index) }
                } }
            }
            LazyVerticalGrid(state = episodeScroll, columns = GridCells.Adaptive(120.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(vod.lines[state.selectedLine].episodes, key = { _, episode -> episode.id }) { index, episode ->
                    TvAction(episode.name, (if (index == rememberedIndex) Modifier.focusRequester(episodeFocus) else Modifier).onFocusChanged {
                        if (it.isFocused) { vm.focusMemory.positions["episodes"] = episode.id; vm.focusMemory.activeRow = "episodes" }
                    }) { vm.focusMemory.positions["episodes"] = episode.id; vm.focusMemory.activeRow = "episodes"; onPlay(PlaybackRequest(vod, state.selectedLine, index)) }
                }
            }
        }
    }
    }
    if (sourceDialog) androidx.compose.material3.AlertDialog(onDismissRequest = { sourceDialog = false },
        title = { Text(stringResource(R.string.choose_source)) },
        text = { androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.switching) item { YunoLoadingState(stringResource(R.string.searching)) }
            state.alternatives.forEach { candidate -> item(key = candidate.identity) {
                TvAction(candidate.sourceName) { vm.switchSource(candidate); sourceDialog = false }
            } }
            if (!state.switching && state.alternatives.isEmpty()) item { Text(stringResource(R.string.no_search_results)) }
        } }, confirmButton = { TvAction(stringResource(R.string.cancel)) { sourceDialog = false } })
}

private fun detailActionModifier(id: String, memory: FocusMemory, requester: FocusRequester): Modifier =
    (if (memory.positions["actions"].orEmpty().ifBlank { "play" } == id) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { if (it.isFocused) { memory.activeRow = "actions"; memory.positions["actions"] = id } }
