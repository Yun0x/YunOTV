package com.yuno.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.yuno.tv.core.database.ResolvePlaybackUseCase
import com.yuno.tv.core.model.*
import com.yuno.tv.core.player.PlayerFactory
import com.yuno.tv.core.ui.*
import com.yuno.tv.feature.home.*
import com.yuno.tv.feature.search.*
import com.yuno.tv.feature.detail.*
import com.yuno.tv.feature.player.*
import com.yuno.tv.feature.history.*
import com.yuno.tv.feature.favorite.*
import com.yuno.tv.feature.settings.*
import com.yuno.tv.feature.source.*
import com.yuno.tv.feature.live.*
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.yuno.tv.core.ui.R as UiR

enum class Destination(val label: Int) {
    HOME(UiR.string.home), SEARCH(UiR.string.search), LIVE(UiR.string.live),
    HISTORY(UiR.string.history), FAVORITE(UiR.string.favorite), SETTINGS(UiR.string.settings)
}

@androidx.media3.common.util.UnstableApi
@Composable
fun YunoApp() {
    val context = LocalContext.current.applicationContext
    val container = (context as YunoApplication).container
    val ready by container.configs.ready.collectAsStateWithLifecycle()
    val playerFactory = remember { PlayerFactory(context, container.network.http) }
    val home: HomeViewModel = viewModel(factory = ModelFactory { HomeViewModel(container.configs, container.library, container.providers, container.settings, context.filesDir.resolve("media-cache")) })
    val search: SearchViewModel = viewModel(factory = ModelFactory { SearchViewModel(container.configs, container.library, container.search) })
    val history: HistoryViewModel = viewModel(factory = ModelFactory { HistoryViewModel(container.library) })
    val favorite: FavoriteViewModel = viewModel(factory = ModelFactory { FavoriteViewModel(container.library) })
    val settings: SettingsViewModel = viewModel(factory = ModelFactory { SettingsViewModel(container.settings, container.configs, container.library, container.logger, container.runtime) })
    val sources: SourceViewModel = viewModel(factory = ModelFactory { SourceViewModel(container.configs, context, container.providers, container.runtime) })
    val live: LiveViewModel = viewModel(factory = ModelFactory { LiveViewModel(container.configs, container.library, playerFactory) })
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var detailVod by remember { mutableStateOf<UnifiedVod?>(null) }
    var activePlayback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var playbackToken by rememberSaveable { mutableStateOf("") }
    val stateHolder = rememberSaveableStateHolder()
    val navFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    val showDetail: (UnifiedVod) -> Unit = { detailVod = it }
    val startPlayback: (PlaybackRequest) -> Unit = { request ->
        playbackToken = UUID.randomUUID().toString(); activePlayback = request
    }
    val resume: (HistoryRecord) -> Unit = { startPlayback(ResolvePlaybackUseCase()(it.vod, it)) }
    val detailModel: DetailViewModel? = if (detailVod != null) {
        val seed = detailVod!!
        screenModel(seed.identity) { DetailViewModel(seed, container.configs, container.library, container.providers) }
    } else null
    if (activePlayback != null) {
        val request = activePlayback!!
        val vm = screenModel(playbackToken) {
            PlayerViewModel(request, playerFactory, container.library, container.settings, container.scope, container.logger,
                resolvePlayback = { container.playback.resolve(it,container.configs.config.value) },
                playbackOutcome = { item, success -> container.configs.config.value.sites.firstOrNull { it.id==item.vod.sourceId }?.let { site ->
                    container.providers.healthChecker.playback(site,item,success)
                }; Unit }, lineScore = { item,index -> container.providers.healthChecker.lineScore(item.vod.sourceId,item.vod.lines[index].id) })
        }
        PlayerScreen(vm) { activePlayback = null }
        return
    }
    BackHandler(detailVod != null || showSources || destination != Destination.HOME) {
        when { detailVod != null -> detailVod = null; showSources -> showSources = false; else -> destination = Destination.HOME }
    }
    LaunchedEffect(Unit) {
        val saved = container.settings.settings.first().lastDestination
        if (detailVod == null) destination = Destination.entries.firstOrNull { it.name == saved } ?: Destination.HOME
        navFocus.getValue(destination).requestFocus()
    }
    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(destination, detailVod, showSources) {
        pageAlpha.snapTo(.85f)
        pageAlpha.animateTo(1f, tween(160))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            YunoBrand(Modifier.padding(end = 12.dp))
            Destination.entries.forEach { item ->
                TvAction(stringResource(item.label), Modifier.focusRequester(navFocus.getValue(item)), selected = item == destination) {
                    detailVod = null; showSources = false; destination = item
                    container.scope.launch { container.settings.update { it.copy(lastDestination = item.name) } }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = pageAlpha.value }) {
            if (!ready) EmptyState(stringResource(R.string.app_name), stringResource(UiR.string.loading))
            else if (detailVod != null) {
                stateHolder.SaveableStateProvider("detail:${detailVod!!.identity}") {
                    DetailScreen(detailModel!!, startPlayback) { detailVod = null }
                }
            } else if (showSources) SourceScreen(sources)
            else stateHolder.SaveableStateProvider(destination.name) {
                when (destination) {
                    Destination.HOME -> HomeScreen(home, { showSources = true }, showDetail, resume, startPlayback)
                    Destination.SEARCH -> SearchScreen(search, showDetail)
                    Destination.HISTORY -> HistoryScreen(history, resume)
                    Destination.FAVORITE -> FavoriteScreen(favorite, showDetail)
                    Destination.LIVE -> LiveScreen(live, startPlayback) { showSources = true }
                    Destination.SETTINGS -> SettingsScreen(settings, context) { showSources = true }
                }
            }
        }
    }
}

/** Detail/player have their own lifetime; leaving a player always releases ExoPlayer. */
@Composable
private fun <VM : ViewModel> screenModel(key: String, create: () -> VM): VM {
    val owner = remember(key) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return remember(owner) {
        val model = create()
        ViewModelProvider(owner, ModelFactory { model })[model.javaClass]
    }
}
