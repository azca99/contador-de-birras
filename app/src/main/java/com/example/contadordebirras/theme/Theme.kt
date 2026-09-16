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
    secondary = AmberTostado,
    tertiary = BottleGreen,
    background = Charcoal,
    surface = Charcoal,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = SpanishCream,
    onSecondary = Charcoal,
    onTertiary = SpanishCream,
    onBackground = SpanishCream,
    onSurface = SpanishCream,
    onSurfaceVariant = SpanishCream,
    error = DarkRedError,
    outline = OutlineDark,
    outlineVariant = SurfaceVariantDark,
    primaryContainer = SpanishRed,
    onPrimaryContainer = SpanishCream,
    secondaryContainer = AmberTostado,
    onSecondaryContainer = Charcoal
)

private val LightColorScheme = lightColorScheme(
    primary = SpanishRed,
    secondary = AmberTostado,
    tertiary = BottleGreen,
    background = SpanishCream,
    surface = SpanishCream,
    surfaceVariant = DarkCream,
    onPrimary = SpanishCream,
    onSecondary = Charcoal,
    onTertiary = SpanishCream,
    onBackground = Charcoal,
    onSurface = Charcoal,
    onSurfaceVariant = Charcoal,
    error = DarkRedError,
    outline = OutlineLight,
    outlineVariant = DarkCream,
    primaryContainer = SpanishRed,
    onPrimaryContainer = SpanishCream,
    secondaryContainer = AmberTostado,
    onSecondaryContainer = Charcoal
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
