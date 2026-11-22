package fr.spotifeur.spotifire.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.spotifeur.R
import fr.spotifeur.presentation.viewmodel.ColorSchemePair
import fr.spotifeur.ui.theme.SpotifeurTheme

/** The Spotify palette, as used by the official Android app. */
object SpotifyColors {
    val Green = Color(0xFF1ED760)
    val GreenDark = Color(0xFF1DB954)
    val Background = Color(0xFF000000)
    val Black = Color(0xFF000000)
    val Elevated = Color(0xFF181818)
    val Highlight = Color(0xFF2A2A2A)
    val Card = Color(0xFF121212)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF7A7A7A)
    val Divider = Color(0xFF2A2A2A)
    val YouTubeRed = Color(0xFFFF0033)
    val LocalBlue = Color(0xFF509BF5)
    val Error = Color(0xFFF15E6C)
}

/** Spotify Mix, shipped with the Spotui sources. Medium/SemiBold map to the nearest cut. */
val SpotifyMix = FontFamily(
    Font(R.font.spotify_mix_ui_regular, weight = FontWeight.Normal),
    Font(R.font.spotify_mix_ui_regular, weight = FontWeight.Medium),
    Font(R.font.spotify_mix_ui_bold, weight = FontWeight.SemiBold),
    Font(R.font.spotify_mix_ui_bold, weight = FontWeight.Bold),
)

val SpotifyMixTitle = FontFamily(
    Font(R.font.spotify_mix_ui_title_bold, weight = FontWeight.Bold),
    Font(R.font.spotify_mix_ui_title_extrabold, weight = FontWeight.Black),
)

private val body = TextStyle(fontFamily = SpotifyMix, letterSpacing = (-0.2).sp)
private val title = TextStyle(fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)

val SpotifyTypography = Typography(
    displayLarge = title.copy(fontSize = 48.sp), displayMedium = title.copy(fontSize = 40.sp), displaySmall = title.copy(fontSize = 34.sp),
    headlineLarge = title.copy(fontSize = 30.sp), headlineMedium = title.copy(fontSize = 26.sp), headlineSmall = title.copy(fontSize = 22.sp),
    titleLarge = body.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = body.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
    bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = body.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelMedium = body.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = body.copy(fontSize = 11.sp),
)

val SpotifyColorScheme = darkColorScheme(
    primary = SpotifyColors.Green,
    onPrimary = Color.Black,
    primaryContainer = SpotifyColors.GreenDark,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = SpotifyColors.Highlight,
    onSecondaryContainer = Color.White,
    tertiary = SpotifyColors.Green,
    onTertiary = Color.Black,
    tertiaryContainer = SpotifyColors.Highlight,
    onTertiaryContainer = Color.White,
    background = SpotifyColors.Background,
    onBackground = Color.White,
    surface = SpotifyColors.Background,
    onSurface = Color.White,
    surfaceVariant = SpotifyColors.Highlight,
    onSurfaceVariant = SpotifyColors.TextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = SpotifyColors.GreenDark,
    surfaceBright = SpotifyColors.Highlight,
    surfaceDim = SpotifyColors.Black,
    surfaceContainerLowest = SpotifyColors.Black,
    surfaceContainerLow = SpotifyColors.Card,
    surfaceContainer = SpotifyColors.Elevated,
    surfaceContainerHigh = SpotifyColors.Highlight,
    surfaceContainerHighest = Color(0xFF333333),
    outline = SpotifyColors.TextMuted,
    outlineVariant = SpotifyColors.Divider,
    error = SpotifyColors.Error,
    onError = Color.Black,
    errorContainer = Color(0xFF5C1A22),
    onErrorContainer = Color.White,
    scrim = Color.Black,
)

/**
 * Always dark, like Spotify. It goes through [SpotifeurTheme] so the screens kept from
 * Spotifeur (settings, equalizer, converter) still find their composition locals, but
 * with the Spotify palette and fonts instead of dynamic colors.
 */
@Composable
fun SpotifireTheme(content: @Composable () -> Unit) {
    SpotifeurTheme(darkTheme = true, colorSchemePairOverride = ColorSchemePair(SpotifyColorScheme, SpotifyColorScheme)) {
        MaterialTheme(colorScheme = SpotifyColorScheme, typography = SpotifyTypography, shapes = MaterialTheme.shapes) {
            ProvideTextStyle(LocalTextStyle.current.merge(body).copy(color = Color.White), content = content)
        }
    }
}
