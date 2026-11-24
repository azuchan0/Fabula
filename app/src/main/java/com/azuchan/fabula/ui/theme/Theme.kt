package com.azuchan.fabula.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.azuchan.fabula.R

/** Barlow (SIL OFL 1.1, assets/licenses/Barlow-OFL.txt): a DIN-like grotesque for camera readouts. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

private fun TextStyle.inBarlow() = copy(fontFamily = Barlow)

private val BarlowTypography = Typography().run {
    copy(
        displayLarge = displayLarge.inBarlow(),
        displayMedium = displayMedium.inBarlow(),
        displaySmall = displaySmall.inBarlow(),
        headlineLarge = headlineLarge.inBarlow(),
        headlineMedium = headlineMedium.inBarlow(),
        headlineSmall = headlineSmall.inBarlow(),
        titleLarge = titleLarge.inBarlow(),
        titleMedium = titleMedium.inBarlow(),
        titleSmall = titleSmall.inBarlow(),
        bodyLarge = bodyLarge.inBarlow(),
        bodyMedium = bodyMedium.inBarlow(),
        bodySmall = bodySmall.inBarlow(),
        labelLarge = labelLarge.inBarlow(),
        labelMedium = labelMedium.inBarlow(),
        labelSmall = labelSmall.inBarlow(),
    )
}

/** Black camera body, white readouts, one red accent for what is active or manual. */
object FabulaColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0E0E0E)
    val Card = Color(0xFF141414)
    val Raised = Color(0xFF1A1A1A)
    val Selected = Color(0xFF262626)
    val AccentTint = Color(0xFF2E1416)
    val Accent = Color(0xFFFF5A5F)
    val OnAccent = Color(0xFF2A0507)
    val Text = Color(0xFFF4F4F2)
    val TextSoft = Color(0xFFBDBDB8)
    val TextMuted = Color(0xFF8E8E8A)
    val Line = Color(0xFF3A3A38)
    val Hairline = Color(0xFF232323)
    val Error = Color(0xFFFF8A7A)
}

private val CameraScheme = darkColorScheme(
    primary = FabulaColors.Accent,
    onPrimary = FabulaColors.OnAccent,
    primaryContainer = FabulaColors.AccentTint,
    onPrimaryContainer = FabulaColors.Accent,
    secondary = FabulaColors.TextSoft,
    onSecondary = FabulaColors.Background,
    secondaryContainer = FabulaColors.Raised,
    onSecondaryContainer = FabulaColors.Text,
    tertiary = FabulaColors.Accent,
    background = FabulaColors.Background,
    onBackground = FabulaColors.Text,
    surface = FabulaColors.Surface,
    onSurface = FabulaColors.Text,
    surfaceVariant = FabulaColors.Card,
    onSurfaceVariant = FabulaColors.TextMuted,
    surfaceContainerLowest = FabulaColors.Background,
    surfaceContainerLow = FabulaColors.Surface,
    surfaceContainer = FabulaColors.Card,
    surfaceContainerHigh = FabulaColors.Raised,
    surfaceContainerHighest = FabulaColors.Selected,
    outline = FabulaColors.Line,
    outlineVariant = FabulaColors.Hairline,
    error = FabulaColors.Error,
)

@Composable
fun FabulaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CameraScheme, typography = BarlowTypography, content = content)
}
