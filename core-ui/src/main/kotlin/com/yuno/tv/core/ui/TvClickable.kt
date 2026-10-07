package com.yuno.tv.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick as semanticClick

/** TV controls remain focusable even after touch input on a phone or TV touchpad. */
@Composable
fun Modifier.tvClickable(onClick: () -> Unit): Modifier {
    val action by rememberUpdatedState(onClick)
    return onPreviewKeyEvent { event ->
        if (event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) action()
            true
        } else false
    }.pointerInput(Unit) { detectTapGestures(onTap = { action() }) }
        .semantics(mergeDescendants = true) { role = Role.Button; semanticClick { action(); true } }
        .focusable()
}
