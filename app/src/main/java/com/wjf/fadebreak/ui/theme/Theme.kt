package com.wjf.fadebreak.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 日间:翡翠绿;夜间:浅雾绿。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E7A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFADF0CE),
    onPrimaryContainer = Color(0xFF002115),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DA),
    onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF3B6472),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF161D18),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D18),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404942),
    outline = Color(0xFF707970)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C9B5),
    onPrimary = Color(0xFF22392B),
    primaryContainer = Color(0xFF3A5245),
    onPrimaryContainer = Color(0xFFC4DDCC),
    secondary = Color(0xFFBDC9C0),
    onSecondary = Color(0xFF28352D),
    secondaryContainer = Color(0xFF414E45),
    onSecondaryContainer = Color(0xFFD9E3DC),
    tertiary = Color(0xFFAEC5BF),
    onTertiary = Color(0xFF1B3430),
    background = Color(0xFF151715),
    onBackground = Color(0xFFE3E5E1),
    surface = Color(0xFF1C1E1C),
    onSurface = Color(0xFFE3E5E1),
    surfaceVariant = Color(0xFF454C45),
    onSurfaceVariant = Color(0xFFC6CDC6),
    outline = Color(0xFF909790)
)

@Composable
fun FadeBreakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
