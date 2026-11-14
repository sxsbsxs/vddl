package com.videofetch.pro.ui.theme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
private val VideoFetchColors = lightColorScheme(
    primary = BrandPrimary, onPrimary = AppSurface, secondary = BrandSecondary,
    background = AppBackground, onBackground = AppInk, surface = AppSurface,
    onSurface = AppInk, outline = AppOutline
)
@Composable
fun VideoFetchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VideoFetchColors, typography = MaterialTheme.typography, content = content)
}
