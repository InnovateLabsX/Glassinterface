package com.glassinterface.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Custom colors for GlassInterface — accessibility-focused palette
private val GlassPrimary = Color(0xFF00BFA5)       // Teal accent
private val GlassOnPrimary = Color(0xFFFFFFFF)
private val GlassSecondary = Color(0xFF26C6DA)      // Cyan
private val GlassBackground = Color(0xFF121212)      // Deep dark
private val GlassSurface = Color(0xFF1E1E1E)
private val GlassError = Color(0xFFFF5252)

private val DarkColorScheme = darkColorScheme(
    primary = GlassPrimary,
    onPrimary = GlassOnPrimary,
    secondary = GlassSecondary,
    background = GlassBackground,
    surface = GlassSurface,
    error = GlassError,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color.White,
    secondary = Color(0xFF0097A7),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = Color(0xFFD32F2F)
)

@Composable
fun GlassInterfaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
