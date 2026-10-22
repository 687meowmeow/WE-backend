package com.wepwawet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

// WepWawet is dark-only — the WE Android app itself is dark, and the user is going to
// be looking at this on the same device they want wallpapers on. No light theme.
private val WepDarkColors = darkColorScheme(
    primary = WepPrimary,
    onPrimary = WepOnPrimary,
    primaryContainer = WepPrimaryContainer,
    onPrimaryContainer = WepOnPrimaryContainer,
    secondary = WepSecondary,
    onSecondary = WepOnSecondary,
    secondaryContainer = WepSecondaryContainer,
    onSecondaryContainer = WepOnSecondaryContainer,
    error = WepError,
    onError = WepOnError,
    errorContainer = WepErrorContainer,
    onErrorContainer = WepOnErrorContainer,
    background = WepBackground,
    onBackground = WepOnBackground,
    surface = WepSurface,
    onSurface = WepOnSurface,
    surfaceVariant = WepSurfaceVariant,
    onSurfaceVariant = WepOnSurfaceVariant,
    surfaceTint = WepSurfaceTint,
    outline = WepOutline,
    outlineVariant = WepOutlineVariant,
)

@Composable
fun WepWawetTheme(
    // The user has a rooted flagship — dynamic color (Material You) is supported but we keep
    // our own palette by default since it's tuned for a dark wallpaper-gallery feel.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)
        else -> WepDarkColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WepTypography,
        content = content,
    )
}
