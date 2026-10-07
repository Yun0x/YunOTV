package com.yuno.tv.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yuno.tv.core.model.UnifiedVod

/** Owned by a screen ViewModel so focus survives detail navigation. */
class FocusMemory { val positions = mutableStateMapOf<String, String>(); var activeRow by mutableStateOf("") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(title: String, rowId: String, items: List<UnifiedVod>, focusMemory: FocusMemory,
    subtitle: @Composable (UnifiedVod) -> String = { it.remarks.ifBlank { listOf(it.year, it.type, it.sourceName).filter(String::isNotBlank).joinToString(" · ") } },
    onLoadMore: (() -> Unit)? = null, onClick: (UnifiedVod) -> Unit) {
    val selected = focusMemory.positions[rowId]
    val initialIndex = items.indexOfFirst { it.identity == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fallback = remember { FocusRequester() }
    LaunchedEffect(rowId) {
        if (selected != null && focusMemory.activeRow == rowId && items.isNotEmpty()) {
            withFrameNanos { }
            if (focusMemory.activeRow == rowId && focusMemory.positions[rowId] == selected) fallback.requestFocus()
        }
    }
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(10.dp), modifier = Modifier.focusRestorer(fallback).focusGroup()) {
            itemsIndexed(items, key = { _, item -> item.identity }) { index, vod ->
                PosterCard(vod, subtitle(vod),
                    if (index == initialIndex) Modifier.focusRequester(fallback) else Modifier,
                    onFocused = { focusMemory.positions[rowId] = vod.identity; focusMemory.activeRow = rowId; if (index >= items.size - 3) onLoadMore?.invoke() },
                    onClick = {
                        focusMemory.positions[rowId] = vod.identity
                        focusMemory.activeRow = rowId
                        onClick(vod)
                    })
            }
        }
    }
}

@Composable
fun PosterCard(vod: UnifiedVod, subtitle: String, modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) YunoFocusStyle.PosterScale else 1f, animationSpec = YunoMotion.focus(), label = "posterFocus")
    Column(modifier.width(132.dp).scale(scale).onFocusChanged {
        focused = it.isFocused; if (it.isFocused) onFocused()
    }.shadow(if (focused) 10.dp else 0.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).border(if (focused) 2.dp else 0.dp,
        if (focused) CinemaColors.accent else Color.Transparent, RoundedCornerShape(10.dp))
        .background(CinemaColors.panel).tvClickable(onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().height(186.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF243041)), contentAlignment = Alignment.Center) {
            Text(vod.name.take(1), color = CinemaColors.accent, style = MaterialTheme.typography.displayMedium)
            AsyncImage(model = rememberYunoImage(vod.poster, 264, 396),
                contentDescription = vod.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(vod.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        Text(subtitle, color = CinemaColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}
