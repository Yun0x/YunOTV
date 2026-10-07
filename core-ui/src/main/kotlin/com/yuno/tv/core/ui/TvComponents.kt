package com.yuno.tv.core.ui

import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme

@Composable
fun TvAction(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) YunoFocusStyle.ActionScale else 1f, animationSpec = YunoMotion.focus(), label = "actionFocus")
    val shape = YunoShapes.L
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(if (focused) YunoColors.SurfaceFocused else if (selected) YunoColors.SurfaceElevated else CinemaColors.panel)
        .border(if (focused || selected) 2.dp else 0.dp, if (focused || selected) CinemaColors.accent else androidx.compose.ui.graphics.Color.Transparent, shape)
        .tvClickable(onClick)
        .defaultMinSize(minHeight = 40.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (focused) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(message, color = CinemaColors.muted)
        if (action != null) { Spacer(Modifier.height(24.dp)); TvAction(action, onClick = onAction) }
    }
}
