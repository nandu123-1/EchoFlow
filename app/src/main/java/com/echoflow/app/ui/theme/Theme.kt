package com.echoflow.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * EchoFlow Material 3 theme — Ocean Breeze visual direction.
 * Calm, intelligent, ambient, and modern.
 */
private val EchoFlowColorScheme = lightColorScheme(
    primary = OceanPrimary,
    onPrimary = EchoTextOnPrimary,
    primaryContainer = EchoPrimaryContainer,
    secondary = OceanSecondary,
    onSecondary = EchoTextOnPrimary,
    secondaryContainer = EchoSecondaryContainer,
    tertiary = OceanTertiary,
    background = OceanBackground,
    onBackground = EchoTextPrimary,
    surface = OceanSurface,
    onSurface = EchoTextPrimary,
    surfaceVariant = OceanSurfaceVariant,
    onSurfaceVariant = EchoTextSecondary,
    error = EchoError,
    onError = EchoTextOnPrimary,
    errorContainer = EchoErrorContainer,
    outline = OceanCardBorder,
)

@Composable
fun EchoFlowTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = OceanBackground.toArgb()
            window.navigationBarColor = OceanBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = EchoFlowColorScheme,
        typography = EchoFlowTypography,
        content = content
    )
}
