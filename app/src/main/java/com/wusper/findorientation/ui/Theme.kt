package com.wusper.findorientation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF071018)
private val Phosphor = Color(0xFF39F3C3)
private val Amber = Color(0xFFFFB020)

@Composable
fun FindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Color(0xFF0D1B24),
            primary = Phosphor,
            secondary = Amber,
            onBackground = Color(0xFFE7FFF8),
            onSurface = Color(0xFFE7FFF8)
        ),
        content = content
    )
}
