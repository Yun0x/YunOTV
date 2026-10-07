package com.yuno.tv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuno.tv.core.*
import com.yuno.tv.core.config.ConfigRepository
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.*
import com.yuno.tv.core.network.SearchAggregator
import com.yuno.tv.core.ui.FocusMemory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchUiState(val keyword: String = "", val loading: Boolean = false,
    val results: List<UnifiedVod> = emptyList(), val variants: Map<String, List<UnifiedVod>> = emptyMap(), val failedSources: Int = 0, val searched: Boolean = false, val completed: Int = 0, val total: Int = 0)
class SearchViewModel(private val config: ConfigRepository, private val library: LibraryRepository,
    private val aggregator: SearchAggregator) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()
    val history = library.searches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val focusMemory = FocusMemory()
    private var job: Job? = null
    private var debounce: Job? = null
    private val useCase = com.yuno.tv.core.network.SearchUseCase(aggregator)
    fun input(value: String) {
        job?.cancel(); debounce?.cancel()
        _state.update { it.copy(keyword = value.take(100), loading = false) }
        if (value.isBlank()) { _state.update { it.copy(results = emptyList(), searched = false) }; return }
        debounce = viewModelScope.launch { delay(400); search(value, saveHistory = false) }
    }
    fun search(value: String = _state.value.keyword, saveHistory: Boolean = true, more: Boolean = false) {
        val keyword = value.trim(); if (keyword.isBlank()) return
        if (saveHistory) debounce?.cancel()
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(keyword = keyword, loading = true, searched = true) }
            try {
                if (saveHistory) library.saveSearch(keyword)
                aggregator.stream(keyword, config.config.value.sites, more).collect { result ->
                    _state.update { it.copy(loading = !result.finished, results = result.results, variants = result.variants,
                        failedSources = result.errors.size, completed = result.completed, total = result.total) }
                }
            } catch (error: Exception) { error.rethrowCancellation(); _state.update { it.copy(loading = false, failedSources = 1) } }
        }
    }
    fun searchMore() { search(saveHistory = false, more = true) }
    fun clearHistory() { viewModelScope.launch { library.clearSearches() } }
}
