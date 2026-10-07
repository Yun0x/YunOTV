package com.yuno.tv.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yuno.tv.core.model.UnifiedVod
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R

@Composable
fun SearchScreen(vm: SearchViewModel, onDetail: (UnifiedVod) -> Unit) {
    val resultScroll = rememberLazyListState()
    var editing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val firstKey = remember { androidx.compose.ui.focus.FocusRequester() }
    var choices by remember { mutableStateOf<List<UnifiedVod>?>(null) }
    LaunchedEffect(Unit) { withFrameNanos { };firstKey.requestFocus();keyboard?.hide() }
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.width(325.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.keyword, vm::input,
                readOnly = !editing, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(showKeyboardOnFocus = false),
                label = { Text(stringResource(R.string.search_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if(event.type==androidx.compose.ui.input.key.KeyEventType.KeyDown && event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_CENTER) { editing=true;keyboard?.show();true }
                    else if(event.type==androidx.compose.ui.input.key.KeyEventType.KeyDown && event.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN) { editing=false;firstKey.requestFocus();keyboard?.hide();true }
                    else false
                })
            // Every key is a TV button; system IME remains available through the text field.
            listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key -> TvAction(key.toString(), Modifier.width(50.dp).then(if(key=='A')Modifier.focusRequester(firstKey)else Modifier)) { vm.input(state.keyword + key) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(stringResource(R.string.backspace)) { vm.input(state.keyword.dropLast(1)) }
                TvAction(stringResource(R.string.clear_input)) { vm.input("") }
                TvAction(stringResource(R.string.search)) { vm.search() }
            }
            if (history.isNotEmpty()) {
                Text(stringResource(R.string.search_history))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { entry -> item(key = entry.keyword) { TvAction(entry.keyword) { vm.search(entry.keyword) } } }
                    item { TvAction(stringResource(R.string.clear_search_history), onClick = vm::clearHistory) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = resultScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { YunoLoadingState(stringResource(R.string.search_progress, state.completed, state.total)) }
            if (state.failedSources > 0) item { Text(stringResource(R.string.search_failed_count, state.failedSources), color = CinemaColors.muted) }
            state.results.chunked(4).forEachIndexed { index, items -> item(key = index) {
                MediaRow(if (index == 0) stringResource(R.string.search_results, state.results.size) else "", "results:$index", items, vm.focusMemory,
                    subtitle = { listOf(it.year, it.type, it.sourceName).filter(String::isNotBlank).joinToString(" · ") }, onClick = { vod ->
                        val variants = state.variants[vod.identity].orEmpty()
                        if (variants.size > 1) choices = variants else onDetail(vod)
                    })
            } }
            if (state.searched && !state.loading) item { TvAction(stringResource(R.string.search_more), onClick = vm::searchMore) }
            if (state.searched && !state.loading && state.results.isEmpty()) item { Text(stringResource(R.string.no_search_results)) }
        }
    }
    choices?.let { variants -> androidx.compose.material3.AlertDialog(onDismissRequest = { choices = null },
        title = { Text(stringResource(R.string.choose_source)) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            variants.forEach { vod -> item(key = vod.identity) { TvAction(vod.sourceName) { choices = null; onDetail(vod) } } }
        } }, confirmButton = { TvAction(stringResource(R.string.cancel)) { choices = null } }) }

}
