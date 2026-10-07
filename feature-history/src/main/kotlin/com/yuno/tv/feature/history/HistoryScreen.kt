package com.yuno.tv.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.ui.focus.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.HistoryRecord
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

class HistoryViewModel(private val library: LibraryRepository) : ViewModel() {
    val focusMemory = FocusMemory()
    val history = library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun delete(id: String) { viewModelScope.launch { library.deleteHistory(id) } }
    fun clear() { viewModelScope.launch { library.clearHistory() } }
}
@Composable
fun HistoryScreen(vm: HistoryViewModel, onResume: (HistoryRecord) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val selectedId = vm.focusMemory.positions["history"]
    val selectedIndex = history.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (selectedId != null && history.isNotEmpty()) { withFrameNanos { }; restore.requestFocus() } }
    var clear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvAction(stringResource(R.string.clear_history)) { clear = true }
        if (history.isEmpty()) EmptyState(stringResource(R.string.no_history), stringResource(R.string.history_hint))
        LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            history.forEach { entry -> item(key = entry.id) {
                Row(Modifier.fillMaxWidth().background(YunoColors.Surface, YunoShapes.L).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    AsyncImage(rememberYunoImage(entry.vod.poster, 144, 216), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(60.dp).height(90.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.vod.name + " · " + entry.episodeName)
                        Text(entry.vod.sourceName + " · " + stringResource(if (entry.progress >= .95f) R.string.completed_percent else R.string.resume_percent,
                            (entry.progress * 100).toInt()), color = CinemaColors.muted)
                    }
                    TvAction(stringResource(R.string.continue_watching), (if (entry.id == history.getOrNull(selectedIndex)?.id) Modifier.focusRequester(restore) else Modifier).onFocusChanged {
                        if (it.isFocused) vm.focusMemory.positions["history"] = entry.id
                    }) { vm.focusMemory.positions["history"] = entry.id; onResume(entry) }
                    TvAction(stringResource(R.string.delete)) { vm.delete(entry.id) }
                }
            } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(stringResource(R.string.clear_history_confirm)) },
        confirmButton = { TvAction(stringResource(R.string.clear_history)) { vm.clear(); clear = false } },
        dismissButton = { TvAction(stringResource(R.string.cancel)) { clear = false } })
}
