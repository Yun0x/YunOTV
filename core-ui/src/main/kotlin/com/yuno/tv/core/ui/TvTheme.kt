package com.yuno.tv.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object CinemaColors {
    val background = YunoColors.BackgroundPrimary
    val panel = YunoColors.Surface
    val accent = YunoColors.Primary
    val muted = YunoColors.TextSecondary
}

@Composable
fun YunoTheme(theme: String = "DARK", content: @Composable () -> Unit) {
    val background = if (theme == "OLED") Color.Black else CinemaColors.background
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = CinemaColors.accent, background = background,
            surface = CinemaColors.panel, onSurface = Color.White
        )
    ) {
        MaterialTheme(typography = YunoTypography.styles, shapes = androidx.tv.material3.Shapes(small = YunoShapes.S, medium = YunoShapes.M, large = YunoShapes.L, extraLarge = YunoShapes.XL), colorScheme = darkColorScheme(
            primary = CinemaColors.accent, background = background,
            surface = CinemaColors.panel, onSurface = Color.White
        )) { CompositionLocalProvider(LocalContentColor provides Color.White, content = content) }
    }
}
