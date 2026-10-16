package com.music.sonariq.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.ui.haptics.Haptic
import com.music.sonariq.ui.haptics.rememberHaptics
import com.music.sonariq.ui.replay.cacheForSharing
import com.music.sonariq.ui.replay.sendIntent
import kotlinx.coroutines.launch

/**
 * What to do with the song, Spotify-style.
 *
 * The card is rendered on demand — artwork is already warm in Coil's cache
 * from the player, so the sheet answers instantly and the share target gets a
 * real 1080×1920 picture, never a placeholder.
 *
 * @param lyricLines 2–3 real lines around the current position, or null when
 * the track has no lyrics — the "Share with lyrics" row is then hidden rather
 * than offered with nothing to put on the card.
 * @param trackUrl the shareable link, or null for local files with no link —
 * the "Copy link" row is then hidden.
 */
@Composable
fun SongShareSheet(
    title: String,
    artist: String,
    artworkUrl: String?,
    lyricLines: List<String>?,
    trackUrl: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf(false) }

    /** Renders the card and hands the picture to the app of the user's choice. */
    fun sendCard(lines: List<String>?, text: String? = null) {
        if (busy) return
        busy = true
        haptics.play(Haptic.Tap)
        scope.launch {
            val bitmap = runCatching {
                renderSongShareCard(context, title, artist, artworkUrl, lines)
            }.getOrNull()
            busy = false
            if (bitmap == null) {
                return@launch
            }
            val uri = cacheForSharing(context, bitmap, "song-card.png")
            if (uri == null) {
                return@launch
            }
            val send = sendIntent(uri).apply {
                text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }
            onDismiss()
            context.startActivity(Intent.createChooser(send, title))
        }
    }

    fun copyLink(url: String) {
        haptics.play(Haptic.Tap)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.preparing_share),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ShareRow(
                icon = Icons.Rounded.IosShare,
                label = stringResource(R.string.share_to_story),
                onClick = { sendCard(null) },
            )
            if (!lyricLines.isNullOrEmpty()) {
                ShareRow(
                    icon = Icons.Rounded.FormatQuote,
                    label = stringResource(R.string.share_with_lyrics),
                    onClick = { sendCard(lyricLines) },
                )
            }
            ShareRow(
                icon = Icons.Rounded.Send,
                label = stringResource(R.string.share_song),
                onClick = {
                    sendCard(
                        null,
                        context.getString(R.string.share_song_text, title, artist),
                    )
                },
            )
            trackUrl?.let { url ->
                ShareRow(
                    icon = Icons.Rounded.Link,
                    label = stringResource(R.string.copy_link),
                    onClick = { copyLink(url) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShareRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
