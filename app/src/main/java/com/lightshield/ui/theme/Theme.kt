package com.lightshield.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val YoutubeRed = Color(0xFFFF0000)
private val SurfaceDark = Color(0xFF0F0F0F)
private val OnSurface = Color(0xFFF1F1F1)

private val OTubeDarkColors = darkColorScheme(
    primary = YoutubeRed,
    onPrimary = Color.White,
    background = SurfaceDark,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface
)

@Composable
fun OTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OTubeDarkColors,
        content = content
    )
}
