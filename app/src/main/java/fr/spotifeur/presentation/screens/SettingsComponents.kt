package fr.spotifeur.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import fr.spotifeur.R
import fr.spotifeur.presentation.utils.LocalAppHapticsConfig
import fr.spotifeur.presentation.utils.performAppCompatHapticFeedback
import java.util.Locale

/*
 * Settings in BitChord's arrangement (GPLv3, after BitChord `SettingsSheet.kt`): rows grouped in inset rounded cards,
 * an uppercase header above each card and a plain footer under it, a glyph, a title, an optional subtitle, and on the
 * right the current value with a chevron, a switch or a button. Colours stay Spotify's: black page, #1A1A1A cards,
 * green for what is on.
 */

private val GroupShape = RoundedCornerShape(14.dp)
private val CardColor = Color(0xFF1A1A1A)
private val HairlineColor = Color(0xFF2E2E2E)
private val GreenOn = Color(0xFF1ED760)
/** Longest value shown on the right of a row; longer ones go under the title. */
private const val INLINE_VALUE_CHARS = 18

/** True inside [SettingsGroup]: rows draw no card of their own, the group does. */
private val LocalInGroup = staticCompositionLocalOf { false }

/** A large page title, the first thing in a settings page. */
@Composable
fun SettingsPageTitle(text: String) {
    Text(
        text = text, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}

/**
 * One card of rows, with an optional uppercase [header] above it and a plain-language [footer] below. Rows are
 * separated by hairlines: the card's background shows through a 0.5 dp gap between opaque rows.
 */
@Composable
fun SettingsGroup(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        )
    } else Spacer(Modifier.padding(top = 14.dp))
    Column(
        Modifier.fillMaxWidth().clip(GroupShape).background(HairlineColor),
        verticalArrangement = Arrangement.spacedBy(0.5.dp),
    ) {
        CompositionLocalProvider(LocalInGroup provides true) { content() }
    }
    if (footer != null) {
        Text(
            text = footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

/** A row's own surface: plain inside a group, a small card of its own when it stands alone. */
@Composable
private fun RowSurface(onClick: (() -> Unit)?, enabled: Boolean = true, content: @Composable () -> Unit) {
    val grouped = LocalInGroup.current
    Box(
        Modifier.fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.padding(vertical = 4.dp).clip(GroupShape))
            .background(CardColor)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f),
    ) { content() }
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right [trailing] (a switch) or the current [value]
 * followed by a chevron when the row opens something.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    RowSurface(onClick, enabled) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                Spacer(Modifier.width(16.dp))
            }
            // A short value sits on the right, as in BitChord; a long one goes under the title so it never
            // squeezes the title into a sliver.
            val inline = value != null && value.length <= INLINE_VALUE_CHARS
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (value != null && !inline) Text(value, style = MaterialTheme.typography.bodyMedium, color = GreenOn, maxLines = 2)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
            }
            Spacer(Modifier.width(12.dp))
            when {
                trailing != null -> trailing()
                value != null || onClick != null -> {
                    if (value != null && inline) {
                        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp).padding(end = 2.dp))
                    }
                    if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** Kept for the screens that group with it: a [SettingsGroup] with [title] as header. */
@Composable
fun SettingsSection(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) =
    SettingsGroup(header = title, content = content)

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit = {},
    onClick: () -> Unit,
) = SettingsRow(title = title, subtitle = subtitle, leadingIcon = leadingIcon, onClick = onClick)

@Composable
private fun SettingsSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    Switch(
        checked = checked, enabled = enabled,
        onCheckedChange = { value ->
            performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstantsCompat.GESTURE_START)
            onCheckedChange(value)
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = GreenOn, checkedBorderColor = GreenOn,
            uncheckedThumbColor = Color(0xFFB3B3B3), uncheckedTrackColor = Color(0xFF3A3A3A), uncheckedBorderColor = Color(0xFF3A3A3A),
        ),
    )
}

@Composable
fun SwitchSettingItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) = SettingsRow(
    title = title, subtitle = subtitle, leadingIcon = leadingIcon, enabled = enabled,
    onClick = { onCheckedChange(!checked) },
    trailing = { SettingsSwitch(checked, enabled, onCheckedChange) },
)

/**
 * A choice: the row shows the option in force on the right; tapping it opens the options in a card, the one in force
 * ticked in green, as BitChord's choice pages do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorItem(
    label: String,
    description: String,
    options: Map<String, String>,
    selectedKey: String,
    onSelectionChanged: (String) -> Unit,
    leadingIcon: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(title = label, subtitle = description, leadingIcon = leadingIcon,
        value = options[selectedKey] ?: selectedKey, onClick = { open = true })
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Color(0xFF121212), contentColor = Color.White) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()
                .verticalScroll(rememberScrollState())) {
                Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                SettingsGroup {
                    options.forEach { (key, optionLabel) ->
                        SettingsRow(title = optionLabel, onClick = { onSelectionChanged(key); open = false }, trailing = {
                            if (key == selectedKey) Icon(Icons.Rounded.Check, stringResource(R.string.common_selected), tint = GreenOn)
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun SliderSettingsItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    valueText: (Float) -> String,
) {
    RowSurface(onClick = null) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
            if (leadingIcon != null) {
                Box(Modifier.padding(top = 2.dp).size(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(valueText(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                SpotifySlider(value, onValueChange, valueRange, steps, onValueChangeFinished)
            }
        }
    }
}

/**
 * Spotify's slider: a thin rounded track, green up to a round white thumb, no stop dots or tick marks. Drawn here
 * rather than through Material's expressive slider, whose bar thumb and stop indicator cannot be turned off.
 */
@Composable
private fun SpotifySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val change by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val finish by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)
    fun valueAt(x: Float, width: Int): Float {
        val raw = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Snap to the steps: steps + 1 intervals between the two ends.
        val snapped = if (steps > 0) kotlin.math.round(raw * (steps + 1)) / (steps + 1) else raw
        return valueRange.start + snapped * span
    }
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .semantics {
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target -> change(target.coerceIn(valueRange.start, valueRange.endInclusive)); true }
            }
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset -> change(valueAt(offset.x, size.width)); finish?.invoke() }
            }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> change(valueAt(offset.x, size.width)) },
                    onDragEnd = { finish?.invoke() },
                ) { gesture, _ -> change(valueAt(gesture.position.x, size.width)) }
            },
    ) {
        val track = 4.dp.toPx(); val radius = 8.dp.toPx()
        val y = size.height / 2
        val left = radius; val right = size.width - radius
        val x = left + (right - left) * fraction
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(Color(0xFF3A3A3A), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), track, cap)
        drawLine(GreenOn, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(x, y), track, cap)
        drawCircle(Color.White, radius, androidx.compose.ui.geometry.Offset(x, y))
    }
}

/** A row whose action is a word on the right, in green (red for clearing), rather than a page to open. */
@Composable
fun ActionSettingsItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    enabled: Boolean = true,
) = SettingsRow(
    title = title, subtitle = subtitle, leadingIcon = icon, enabled = enabled,
    trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondaryActionLabel != null && onSecondaryAction != null) ActionWord(secondaryActionLabel, enabled, onSecondaryAction)
            ActionWord(primaryActionLabel, enabled, onPrimaryAction)
        }
    },
)

@Composable
private fun ActionWord(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = GreenOn, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}
