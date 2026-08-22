package com.example.intervaltimer.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Section 7.1 canonical palette, as Compose Color values. Mirrors
 * res/values/colors.xml -- Compose's MaterialTheme reads color from this
 * Kotlin ColorScheme, NOT from the Android View-system themes.xml; the two
 * are independent theming mechanisms. themes.xml still matters (window
 * background before Compose draws, status/nav bar, non-Compose surfaces
 * like the Session 5 notification), but changing it alone would not affect
 * how any Compose screen actually renders. If you ever change a color,
 * update BOTH this file and colors.xml.
 */
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val AccentNeonBlue = Color(0xFF00D9FF)
val AccentBrightGreen = Color(0xFF00FF41)
val OnDark = Color(0xFFFFFFFF)
val DestructiveRed = Color(0xFFFF5252)
val AmberWarning = Color(0xFFFFC107)

private val IntervalTimerDarkColorScheme = darkColorScheme(
    background = BackgroundDark,
    surface = SurfaceDark,
    primary = AccentNeonBlue,
    onPrimary = BackgroundDark,
    secondary = AccentBrightGreen,
    onSecondary = BackgroundDark,
    onBackground = OnDark,
    onSurface = OnDark,
    error = DestructiveRed,
    onError = OnDark
)

/**
 * Section 7.1: dark mode is the ONLY theme -- not toggleable, and this
 * deliberately does NOT branch on isSystemInDarkTheme(). Wrap every screen
 * in this composable.
 */
@Composable
fun IntervalTimerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IntervalTimerDarkColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
