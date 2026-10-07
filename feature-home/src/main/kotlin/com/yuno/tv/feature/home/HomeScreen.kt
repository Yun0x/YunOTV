package com.yuno.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R

@Composable
fun HomeScreen(vm: HomeViewModel, onAddSource: () -> Unit, onDetail: (UnifiedVod) -> Unit, onResume: (HistoryRecord) -> Unit, onPlay: (PlaybackRequest) -> Unit) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val config by vm.sources.collectAsStateWithLifecycle()
    val sourceRecords by vm.sourceRecords.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val unfinished = remember(history) { history.filter { it.progress < .95f }.take(30) }
    val hero = state.content.recent.firstOrNull()
    val rowOrder = remember(hero, unfinished, history, state.content.sections, preferences.homeCategory) { buildList {
        if (hero != null) add("hero")
        if (preferences.homeCategory == "recent" && state.content.recent.isNotEmpty()) add("updates")
        if (unfinished.isNotEmpty()) add("continue")
        if (preferences.homeCategory != "recent" && state.content.recent.isNotEmpty()) add("updates")
        if (history.isNotEmpty()) add("recent_history")
        state.content.sections.forEach { add(it.id) }
    } }
    LaunchedEffect(Unit) { rowOrder.indexOf(vm.focusMemory.activeRow).takeIf { it >= 0 }?.let { listState.scrollToItem(it) } }
    LaunchedEffect(listState, state.content.sections) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key.toString() } }.collect { keys ->
            keys.filter { it.startsWith("section:") }.forEach { vm.loadSection(it.removePrefix("section:")) }
        }
    }
    if (config.sites.isEmpty() && config.lives.isEmpty() && state.content.recent.isEmpty()) {
        if (sourceRecords.any { it.enabled && (it.status == "REFRESHING" || it.status == "NEW") }) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                YunoBrand(large = true)
                YunoLoadingState(stringResource(R.string.loading))
                TvAction(stringResource(R.string.source_management), onClick = onAddSource)
            }; return
        }
        EmptyState(stringResource(R.string.empty_source), stringResource(R.string.empty_guide), stringResource(R.string.add_source), onAddSource); return
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        hero?.let { vod -> item(key = "hero") { Hero(vod, vm.focusMemory, state.heroLoading, onDetail) { vm.playHero(vod, onPlay) } } }
        if (preferences.homeCategory == "recent" && state.content.recent.isNotEmpty()) item(key = "updates") {
            MediaRow(stringResource(R.string.recent_updated), "updates", state.content.recent, vm.focusMemory, onClick = onDetail)
        }
        if (unfinished.isNotEmpty()) item(key = "continue") {
            MediaRow(stringResource(R.string.continue_watching), "continue", unfinished.map { it.vod }, vm.focusMemory,
                subtitle = { vod -> stringResource(R.string.resume_percent, (unfinished.first { it.vod.identity == vod.identity }.progress * 100).toInt()) },
                onClick = { vod -> onResume(unfinished.first { it.vod.identity == vod.identity }) })
        }
        if (preferences.homeCategory != "recent" && state.content.recent.isNotEmpty()) item(key = "updates") {
            MediaRow(stringResource(R.string.recent_updated), "updates", state.content.recent, vm.focusMemory, onClick = onDetail)
        }
        if (history.isNotEmpty()) item(key = "recent_history") { MediaRow(stringResource(R.string.recent_watched), "recent_history", history.take(30).map { it.vod }, vm.focusMemory, onClick = onDetail) }
        state.content.sections.forEach { section -> item(key = "section:${section.id}") {
            if (section.items.isNotEmpty()) MediaRow(section.title, section.id, section.items, vm.focusMemory,
                onLoadMore = { vm.loadSection(section.id, true) }, onClick = onDetail)
            else if (section.id in state.loadingRows) YunoLoadingState(section.title)
            else TvAction(section.title) { vm.loadSection(section.id) }
        } }
        if (state.loading) item(key = "loading") { YunoLoadingState(stringResource(R.string.loading)) }
        if (state.cached) item(key = "cached") { Text(stringResource(R.string.cached_data), color = CinemaColors.muted) }
        if (state.content.errors.isNotEmpty()) item(key = "errors") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.content.errors.entries.take(3).forEach { (name, code) -> Text(name + " · " + errorText(code), color = CinemaColors.muted) }
                TvAction(stringResource(R.string.retry), onClick = vm::refresh)
            }
        }
        if (!state.loading && state.content.recent.isEmpty()) item(key = "empty") {
            Text(stringResource(if (config.lives.isNotEmpty() && config.sites.isEmpty()) R.string.live_only_hint else R.string.error_empty), color = CinemaColors.muted)
            TvAction(stringResource(R.string.source_management), onClick = onAddSource)
        }
    }
}

@Composable
private fun Hero(vod: UnifiedVod, memory: FocusMemory, loading: Boolean, onDetail: (UnifiedVod) -> Unit, onPlay: () -> Unit) {
    val focus = remember { FocusRequester() }
    val selected = memory.positions["hero"].orEmpty().ifBlank { "detail" }
    LaunchedEffect(Unit) { if (memory.activeRow == "hero") { withFrameNanos { }; focus.requestFocus() } }
    Box(Modifier.fillMaxWidth().height(YunoDimensions.HeroHeight).clip(YunoShapes.XL).background(YunoColors.BackgroundSecondary)) {
        AsyncImage(rememberYunoImage(vod.background.ifBlank { vod.poster }, 1280, 720), null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.65f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(YunoColors.BackgroundSecondary,
            YunoColors.BackgroundSecondary.copy(alpha = .86f), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).padding(28.dp).fillMaxWidth(.65f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hero_label), color = YunoColors.Primary, style = MaterialTheme.typography.labelLarge)
            Text(vod.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
            Text(listOf(vod.year, vod.type, vod.sourceName).filter { it.isNotBlank() }.joinToString(" · "), color = CinemaColors.muted)
            Text(vod.description.ifBlank { vod.remarks }, maxLines = 2, color = CinemaColors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(if (loading) R.string.loading else R.string.play), (if (selected == "play") Modifier.focusRequester(focus) else Modifier).onFocusChanged {
                    if (it.isFocused) { memory.activeRow = "hero"; memory.positions["hero"] = "play" }
                }) { memory.activeRow = "hero"; memory.positions["hero"] = "play"; onPlay() }
                TvAction(stringResource(R.string.hero_detail), (if (selected == "detail") Modifier.focusRequester(focus) else Modifier).onFocusChanged {
                    if (it.isFocused) { memory.activeRow = "hero"; memory.positions["hero"] = "detail" }
                }) { memory.activeRow = "hero"; memory.positions["hero"] = "detail"; onDetail(vod) }
            }
        }
    }
}
