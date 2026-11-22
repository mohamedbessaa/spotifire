package fr.spotifeur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.spotifeur.data.preferences.AlbumArtPaletteStyle
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveColorsTest {
    // Coloured covers only: the grey scheme of neutral covers goes through android.graphics.Color.
    private val seeds = listOf(
        0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF0F9D63, 0xFF6FE3A6, 0xFF00ACC1,
        0xFF1E3A8A, 0xFF7E57C2, 0xFFC9BEFF, 0xFFEC407A, 0xFF5D4037, 0xFF8D6E63,
    ).map { Color(it) }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb).toDouble()
    }

    private fun assertContrast(label: String, a: Color, b: Color, min: Double) {
        val ratio = contrast(a, b)
        assertTrue("$label: %.2f < $min".format(ratio), ratio >= min)
    }

    /** What the player draws: text, transport buttons and toggles on its background, and the playing row. */
    private fun assertPlayerReadable(label: String, s: ColorScheme) {
        val background = s.primaryContainer
        assertContrast("$label text", s.onPrimaryContainer, background, 7.0)
        assertContrast("$label play/pause content", s.onTertiaryFixed, s.tertiaryFixedDim, 4.5)
        assertContrast("$label previous/next content", s.onSecondaryFixed, s.secondaryFixedDim, 4.5)
        assertContrast("$label play/pause on background", s.tertiaryFixedDim, background, 3.0)
        assertContrast("$label previous/next on background", s.secondaryFixedDim, background, 3.0)
        assertContrast("$label shuffle content", s.onPrimaryFixed, s.primaryFixed, 4.5)
        assertContrast("$label repeat content", s.onSecondaryFixed, s.secondaryFixed, 4.5)
        assertContrast("$label like content", s.onTertiaryFixed, s.tertiaryFixed, 4.5)
        assertContrast("$label playing row", s.onPrimary, s.primary, 4.5)
    }

    @Test fun artworkSchemesKeepTheirControlsReadableInBothThemes() {
        for (seed in seeds) for (style in AlbumArtPaletteStyle.entries) {
            val pair = generateColorSchemeFromSeed(seed, style)
            // The fallback pair would mean the generation itself failed.
            assertNotEquals(DarkColorScheme.primaryContainer, pair.dark.primaryContainer)
            assertNotEquals(LightColorScheme.primaryContainer, pair.light.primaryContainer)
            val name = "%08X/%s".format(seed.value.toLong() ushr 32, style)
            assertPlayerReadable("$name dark", pair.dark)
            assertPlayerReadable("$name light", pair.light)
            assertTrue("$name dark background", pair.dark.primaryContainer.luminance() < 0.1f)
            assertTrue("$name light background", pair.light.primaryContainer.luminance() > 0.4f)
        }
    }

    @Test fun lightControlsAreDeepAndVividAccentsKeepTones() {
        val light = LightColorScheme.deepControlsForLight()
        assertPlayerReadable("default light", light)
        val vivid = LightColorScheme.vividAccents()
        for ((before, after) in listOf(
            LightColorScheme.primary to vivid.primary,
            LightColorScheme.primaryContainer to vivid.primaryContainer,
            DarkColorScheme.secondaryContainer to DarkColorScheme.vividAccents().secondaryContainer,
        )) {
            assertTrue(kotlin.math.abs(before.luminance() - after.luminance()) < 0.03f)
        }
    }

    @Test fun deepBackdropStaysDarkForWhiteText() {
        for (seed in seeds) {
            assertContrast("backdrop %08X".format(seed.value.toLong() ushr 32), Color.White, toneOf(seed, 26.0, 36.0), 7.0)
        }
    }
}
