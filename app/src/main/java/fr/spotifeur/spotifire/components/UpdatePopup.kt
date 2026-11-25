package fr.spotifeur.spotifire.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.update.AppUpdateChecker

/*
 * The update popup, ported from BitChord's UpdateAvailableDialog (GPLv3): an iOS-style system alert with a frosted
 * card, hairline rules and full-width actions. The card's surface is Spotify's raised grey and the actions use the
 * app's green.
 */

/** UIAlertController's own metrics: fixed narrow width, 14pt corner, 44pt rows. */
private val ALERT_WIDTH = 270.dp
private val ALERT_CORNER = 14.dp
private val ACTION_HEIGHT = 44.dp

/** The dim behind the alert; flat, so the card is what is frosted. */
private val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)
private val DOWNLOAD_ROW_HEIGHT = 4.dp
/** How much of the card's height the release notes may fill before scrolling. */
private val NOTES_MAX_HEIGHT = 220.dp

/**
 * Once-per-launch notice that a newer build is on GitHub Releases. Download pulls the release's APK into the app
 * (progress fills the hairline under the message), then Install hands it to the system installer; where the release
 * has no APK for this phone, the actions open the releases page. An overlay rather than a dialog window, so its glass
 * samples the same [HazeState] as the app's frosted bars.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun UpdatePopup(
    version: String,
    notes: String?,
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by AppUpdateChecker.download.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)
    val scheme = MaterialTheme.colorScheme.copy(surface = SpotifyColors.Elevated, onSurface = Color.White)

    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SCRIM_COLOR)
                // Tapping the scrim reads the same as Remind Me Later; mid-download it only closes the popup.
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(ALERT_WIDTH)
                    .clip(shape)
                    .then(
                        if (hazeState == null) Modifier.background(MaterialTheme.colorScheme.surface)
                        else Modifier.hazeEffect(state = hazeState, style = HazeMaterials.regular(MaterialTheme.colorScheme.surface)),
                    )
                    // Swallows the tap so touching the card never dismisses it.
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Software Update",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = when (state) {
                            is AppUpdateChecker.DownloadState.Downloading -> "Downloading Spotifire $version…"
                            is AppUpdateChecker.DownloadState.Ready -> "Spotifire $version is ready to install."
                            is AppUpdateChecker.DownloadState.Failed -> "Couldn’t download Spotifire $version."
                            else -> "Spotifire $version is available to download."
                        },
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    // The download's progress, a thin fill across a hairline track.
                    val downloading = state as? AppUpdateChecker.DownloadState.Downloading
                    if (downloading != null || state is AppUpdateChecker.DownloadState.Failed) {
                        Box(
                            Modifier.padding(top = 12.dp).fillMaxWidth().height(DOWNLOAD_ROW_HEIGHT)
                                .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                        ) {
                            if (downloading != null && downloading.fraction > 0f) {
                                Box(
                                    Modifier.fillMaxWidth(downloading.fraction).height(DOWNLOAD_ROW_HEIGHT)
                                        .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                    if (state is AppUpdateChecker.DownloadState.Failed) {
                        Text(
                            text = (state as AppUpdateChecker.DownloadState.Failed).message,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // The release's own notes, rendered as Markdown.
                    if (!notes.isNullOrBlank()) {
                        AlertRule(modifier = Modifier.padding(top = 12.dp))
                        Text(
                            text = "What’s new",
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Start,
                        )
                        Box(modifier = Modifier.padding(top = 4.dp).heightIn(max = NOTES_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                                RichText(style = RichTextStyle.Default) { Markdown(notes) }
                            }
                        }
                    }
                }

                AlertRule()
                when (state) {
                    is AppUpdateChecker.DownloadState.Downloading ->
                        AlertAction(label = "Cancel", emphasised = false, onClick = onCancelDownload)
                    is AppUpdateChecker.DownloadState.Ready -> {
                        AlertAction(label = "Install Now", emphasised = true, onClick = onInstall)
                        AlertRule()
                        AlertAction(label = "Later", emphasised = false, onClick = onDismiss)
                    }
                    is AppUpdateChecker.DownloadState.Failed -> {
                        AlertAction(label = "Try Again", emphasised = true, onClick = onDownload)
                        AlertRule()
                        AlertAction(label = "Open Releases Page", emphasised = false, onClick = onOpenReleasePage)
                    }
                    else -> {
                        AlertAction(label = "Download Now", emphasised = true, onClick = onDownload)
                        AlertRule()
                        AlertAction(label = "Remind Me Later", emphasised = false, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/** Full-bleed action row, tinted rather than filled; only the font marks the default action. */
@Composable
private fun AlertAction(label: String, emphasised: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Hairline separator. */
@Composable
private fun AlertRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
}
