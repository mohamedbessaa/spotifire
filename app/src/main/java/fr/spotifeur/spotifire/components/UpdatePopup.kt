package fr.spotifeur.spotifire.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.update.AppUpdateChecker
import fr.spotifeur.update.AppUpdateChecker.DownloadState
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.launch

/**
 * The in-app update popup (as in BitChord): what's new, then Update now or Later. Later closes it until the next
 * launch; the download runs inside the app and ends in the system installer.
 */
@Composable
fun UpdatePopup(update: AppUpdateChecker.UpdateInfo, onLater: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val download by AppUpdateChecker.download.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = { if (download !is DownloadState.Downloading) onLater() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Elevated).padding(24.dp)) {
            Text("Update available", color = Color.White, fontSize = 22.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
            Text("Spotifire ${update.version}", color = SpotifyColors.Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp))
            update.notes?.let { notes ->
                Box(Modifier.padding(top = 16.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(plain(notes), color = SpotifyColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            when (val state = download) {
                is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { state.fraction }, color = SpotifyColors.Green, trackColor = SpotifyColors.Highlight,
                        modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(state.fraction * 100).toInt()}%", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    Buttons("Cancel" to { AppUpdateChecker.cancelDownload() }, null)
                }
                is DownloadState.Ready -> Buttons("Later" to onLater, "Install now" to { AppUpdateChecker.installApk(context, state.file) })
                is DownloadState.Failed -> {
                    Text(state.message, color = SpotifyColors.Error, fontSize = 13.sp)
                    Buttons("Later" to { AppUpdateChecker.resetDownload(); onLater() },
                        "Try again" to { scope.launch { AppUpdateChecker.downloadApk(context.applicationContext) } })
                }
                DownloadState.Idle -> Buttons("Later" to onLater, "Update now" to {
                    if (update.apkUrl != null) scope.launch { AppUpdateChecker.downloadApk(context.applicationContext) }
                    // A release without an APK for this phone: its page, where the file can be picked by hand.
                    else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(update.releaseUrl))) }
                })
            }
        }
    }
}

@Composable
private fun Buttons(secondary: Pair<String, () -> Unit>, primary: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(secondary.first, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).quietClick(secondary.second).padding(horizontal = 16.dp, vertical = 12.dp))
        primary?.let { (label, action) ->
            Spacer(Modifier.padding(4.dp))
            Text(label, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyColors.Green).quietClick(action).padding(horizontal = 22.dp, vertical = 12.dp))
        }
    }
}

/** Release notes are Markdown; the popup shows them as plain text, wrapped lines joined back into their item. */
internal fun plain(markdown: String): String {
    val out = mutableListOf<String>()
    for (raw in markdown.lines()) {
        val continuation = raw.startsWith("  ") && !raw.trimStart().startsWith("-") && !raw.trimStart().startsWith("*") && out.isNotEmpty() && out.last().isNotBlank()
        val line = raw.trim().replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^[-*]\\s+"), "• ").replace("**", "").replace("`", "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        if (continuation) out[out.size - 1] = out.last() + " " + line else out += line
    }
    return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
