package com.wusper.findorientation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF071018)
private val Phosphor = Color(0xFF39F3C3)
private val Amber = Color(0xFFFFB020)
private val InkSurface = Color(0xFF0D1B24)
private val OnInk = Color(0xFFE7FFF8)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = OnInk, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OnInk),
    bodyLarge = TextStyle(fontSize = 16.sp, color = OnInk, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, color = OnInk, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, color = OnInk, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = OnInk)
)

@Composable
fun FindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = InkSurface,
            surfaceVariant = Color(0xFF10232C),
            primary = Phosphor,
            secondary = Amber,
            onBackground = OnInk,
            onSurface = OnInk,
            onPrimary = Color(0xFF05201A),
            onSecondary = Color(0xFF1A1200),
            outline = Color(0xFF2A4A55)
        ),
        typography = typography,
        content = content
    )
}
