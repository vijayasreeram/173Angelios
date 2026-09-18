package org.sih.itantra.presentation.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = IsroBlueBright,
    onPrimary = TextWhite,
    secondary = SignalCyan,
    onSecondary = DeepSpaceNavy,
    tertiary = NetworkGreen,
    background = DeepSpaceNavy,
    surface = SurfaceNavy,
    onBackground = TextWhite,
    onSurface = TextWhite,
    error = EmergencyRed,
    onError = TextWhite
)

@Composable
fun ITANTRATheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = DeepSpaceNavy.toArgb()
                window.navigationBarColor = DeepSpaceNavy.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
