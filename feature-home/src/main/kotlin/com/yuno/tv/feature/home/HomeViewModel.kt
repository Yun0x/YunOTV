package com.yuno.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuno.tv.core.*
import com.yuno.tv.core.config.ConfigRepository
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.*
import com.yuno.tv.core.ui.FocusMemory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

data class HomeUiState(val loading: Boolean = true, val content: HomeContent = HomeContent(emptyList(), emptyList(), emptyMap()), val loadingRows: Set<String> = emptySet(), val cached: Boolean = false, val heroLoading: Boolean = false)
class HomeViewModel(config: ConfigRepository, private val library: LibraryRepository, private val registry: ProviderRegistry,
    settings: com.yuno.tv.core.config.SettingsRepository, cacheDirectory: File? = null) : ViewModel() {
    private val useCase = GetHomeUseCase(registry)
    private val cache = MediaContentCache(cacheDirectory)
    private val configs = config
    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()
    val history = library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val preferences = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerSettings())
    val sources = config.config
    val focusMemory = FocusMemory()
    val sourceRecords = config.sources
    private val pending = mutableMapOf<String, Job>()
    private val semaphore = Semaphore(2)
    private var loadJob: Job? = null
    init { viewModelScope.launch {
        cache.home()?.let { _state.value = HomeUiState(false, it, cached = true) }
        configs.config.map { config -> config.sites.filter { it.supported }.map { it.copy(origins = emptyList()) } }.distinctUntilChanged().collectLatest { load(it) }
    } }
    private var heroJob: Job? = null
    fun playHero(vod: UnifiedVod, onPlay: (PlaybackRequest) -> Unit) {
        if (heroJob?.isActive == true) return
        heroJob = viewModelScope.launch {
            _state.update { it.copy(heroLoading = true) }
            try {
                val source = configs.config.value.sites.firstOrNull { it.id == vod.sourceId } ?: throw SourceException(ErrorCode.UNSUPPORTED)
                val detail = withTimeout(12_000) { registry.provider(source).getDetail(vod.id) }
                if (detail.lines.isEmpty()) throw SourceException(ErrorCode.UNSUPPORTED)
                onPlay(com.yuno.tv.core.database.ResolvePlaybackUseCase()(detail, library.getHistory(detail.identity)))
            } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(content = it.content.copy(errors = it.content.errors + (vod.sourceName to error.friendlyCode()))) } }
            finally { _state.update { it.copy(heroLoading = false) } }
        }
    }
    fun refresh() { loadJob?.cancel(); loadJob = viewModelScope.launch { load(configs.config.value.sites) } }
    private suspend fun load(sites: List<UnifiedSite>) {
        if (sites.isEmpty()) { _state.update { it.copy(loading = false) }; return }
        _state.update { it.copy(loading = true) }
        useCase.stream(sites).collect { content ->
            _state.update { old ->
                if (content.recent.isEmpty() && old.content.recent.isNotEmpty()) old.copy(content = old.content.copy(errors = content.errors))
                else old.copy(content = content.copy(sections = content.sections.map { section ->
                    old.content.sections.firstOrNull { it.id == section.id }?.takeIf { it.items.isNotEmpty() } ?: section
                }), cached = false)
            }
        }
        _state.update { it.copy(loading = false) }
        if (_state.value.content.recent.isNotEmpty()) cache.saveHome(_state.value.content)
    }
    fun loadSection(id: String, more: Boolean = false) {
        if (pending[id]?.isActive == true) return
        val section = _state.value.content.sections.firstOrNull { it.id == id } ?: return
        if (!section.hasMore || (!more && section.items.isNotEmpty())) return
        val site = configs.config.value.sites.firstOrNull { it.id == section.sourceId } ?: return
        pending[id] = viewModelScope.launch {
            _state.update { it.copy(loadingRows = it.loadingRows + id) }
            try {
                val page = if (more) section.page + 1 else 1
                val items = semaphore.withPermit { withTimeout(10_000) { registry.provider(site).getCategory(section.categoryId, page) } }
                _state.update { state -> state.copy(content = state.content.copy(sections = state.content.sections.map {
                    if (it.id == id) it.copy(items = (it.items + items).distinctBy { vod -> vod.identity }.take(120), page = page,
                        hasMore = items.isNotEmpty() && items.any { new -> it.items.none { old -> old.identity == new.identity } } && it.items.size + items.size < 120) else it
                })) }
                cache.saveHome(_state.value.content)
            } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(content = it.content.copy(errors = it.content.errors + (site.name to error.friendlyCode()))) } }
            finally { _state.update { it.copy(loadingRows = it.loadingRows - id) }; pending.remove(id) }
        }
    }
}
