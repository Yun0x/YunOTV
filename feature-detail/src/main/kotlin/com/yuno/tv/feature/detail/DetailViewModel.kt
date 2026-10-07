package com.yuno.tv.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuno.tv.core.*
import com.yuno.tv.core.config.ConfigRepository
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.ProviderRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DetailUiState(val vod: UnifiedVod, val loading: Boolean = true, val error: ErrorCode? = null,
    val selectedLine: Int = 0, val history: HistoryRecord? = null, val favorite: Boolean = false, val alternatives: List<UnifiedVod> = emptyList(), val switching: Boolean = false)
class DetailViewModel(private val seed: UnifiedVod, private val config: ConfigRepository,
    private val library: LibraryRepository, private val registry: ProviderRegistry) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState(seed))
    val state = _state.asStateFlow()
    init {
        load()
        viewModelScope.launch { library.history.collect { records ->
            _state.update { state -> state.copy(history = records.firstOrNull { it.vod.identity == state.vod.identity }) }
        } }
        viewModelScope.launch { library.favorites.collect { favorites ->
            _state.update { it.copy(favorite = favorites.any { record -> record.vod.identity == _state.value.vod.identity }) }
        } }
    }
    val focusMemory = com.yuno.tv.core.ui.FocusMemory()
    private var requestJob: Job? = null
    fun alternatives() { viewModelScope.launch {
        _state.update { it.copy(switching = true) }
        val result = com.yuno.tv.core.network.SearchAggregator(registry).search(_state.value.vod.name, config.config.value.sites)
        _state.update { it.copy(alternatives = result.results.flatMap { item -> result.variants[item.identity].orEmpty() }
            .filter { vod -> vod.name.trim() == it.vod.name.trim() }.distinctBy { vod -> vod.identity }, switching = false) }
    } }
    fun switchSource(vod: UnifiedVod) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val previous = _state.value
            _state.update { it.copy(loading = true, error = null) }
            try {
                val site = config.config.value.sites.firstOrNull { it.id == vod.sourceId } ?: throw SourceException(ErrorCode.UNSUPPORTED)
                val detail = withTimeout(15_000) { registry.provider(site).getDetail(vod.id) }
                val lineName = previous.vod.lines.getOrNull(previous.selectedLine)?.name
                val index = detail.lines.indexOfFirst { it.name == lineName }.coerceAtLeast(0)
                val history = library.getHistory(detail.identity)
                val favorite = library.favorites.first().any { favorite -> favorite.vod.identity == detail.identity }
                _state.update { it.copy(vod = detail, selectedLine = index, loading = false, history = history, favorite = favorite) }
            } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(loading = false, error = error.friendlyCode()) } }
        }
    }
    fun selectLine(index: Int) { _state.update { it.copy(selectedLine = index) } }
    fun toggleFavorite() { viewModelScope.launch {
        try { library.toggleFavorite(_state.value.vod) }
        catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(error = ErrorCode.STORAGE) } }
    } }
    fun load() { requestJob?.cancel(); requestJob = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val history = library.getHistory(seed.identity)
            _state.update { it.copy(history = history, selectedLine = history?.lineIndex ?: 0) }
            val site = config.config.value.sites.firstOrNull { it.id == seed.sourceId }
            val vod = if (site == null && seed.lines.isNotEmpty()) seed else
                withTimeout(15_000) { registry.provider(site ?: throw SourceException(ErrorCode.UNSUPPORTED)).getDetail(seed.id) }
            _state.update { it.copy(vod = vod, loading = false, selectedLine = it.selectedLine.coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0))) }
        } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(loading = false, error = error.friendlyCode()) } }
    } }
}
