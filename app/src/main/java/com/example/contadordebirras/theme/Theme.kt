package com.example.contadordebirras.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = SpanishRed,
    secondary = SpanishRedDark,
    tertiary = DarkCharcoal,
    background = SurfaceDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = DarkCharcoal,
    onSurfaceVariant = Color.White,
    primaryContainer = SpanishRedDark,
    onPrimaryContainer = Color.White,
    secondaryContainer = DarkCharcoal,
    onSecondaryContainer = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = SpanishRed,
    secondary = DarkCharcoal,
    tertiary = SpanishRedDark,
    background = Color.White,
    surface = LightGreySurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = DarkCharcoal,
    onSurface = DarkCharcoal,
    surfaceVariant = LightGreySurface,
    onSurfaceVariant = DarkCharcoal,
    primaryContainer = SpanishRed,
    onPrimaryContainer = Color.White,
    secondaryContainer = SpanishCream,
    onSecondaryContainer = SpanishRed
)

@Composable
fun ContadorDeBirrasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // We disable dynamic color to force our premium Amber/Gold palette
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
