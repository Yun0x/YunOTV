package com.yuno.tv.feature.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuno.tv.core.database.LibraryRepository
import com.yuno.tv.core.model.UnifiedVod
import com.yuno.tv.core.ui.*
import com.yuno.tv.core.ui.R
import kotlinx.coroutines.flow.*

class FavoriteViewModel(library: LibraryRepository) : ViewModel() {
    val favorites = library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val focusMemory = FocusMemory()
}
@Composable
fun FavoriteScreen(vm: FavoriteViewModel, onDetail: (UnifiedVod) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    if (favorites.isEmpty()) EmptyState(stringResource(R.string.no_favorites), stringResource(R.string.favorite_hint))
    else LazyColumn {
        favorites.chunked(7).forEachIndexed { index, records -> item(key = index) {
            MediaRow(if (index == 0) stringResource(R.string.favorite) else "", "favorites:$index", records.map { it.vod }, vm.focusMemory, onClick = onDetail)
        } }
    }
}
