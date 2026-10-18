package com.music.sonariq.ui.discovery

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.music.sonariq.R
import com.music.sonariq.data.discovery.COVER_PALETTES
import com.music.sonariq.data.discovery.CoverMotif
import com.music.sonariq.data.discovery.CoverPalette
import com.music.sonariq.data.discovery.CoverSpec
import com.music.sonariq.data.discovery.PlaylistCovers
import com.music.sonariq.data.discovery.renderCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Makes a custom cover for a playlist: pick colours, a motif and the words,
 * see the real drawing, save it.
 *
 * ## What you see is what is saved
 *
 * The preview is [renderCover] at a small size and the saved file is
 * [renderCover] at [com.music.sonariq.data.discovery.COVER_SIZE_PX] — the
 * same spec, the same drawing, different densities. There is no separate
 * "export" path that could drift from the preview.
 *
 * ## Where the cover goes
 *
 * [PlaylistCovers.save] files it under the playlist's browse id as a local
 * override; the catalogue is never asked to host it because it offers no
 * cover-upload call. [onSaved] tells the host the override changed so it can
 * refresh the artwork it draws.
 */
@Composable
fun CoverMakerScreen(
    playlistBrowseId: String,
    initialTitle: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var title by remember { mutableStateOf(initialTitle) }
    var subtitle by remember { mutableStateOf("") }
    var palette by remember { mutableStateOf(COVER_PALETTES.first()) }
    var motif by remember { mutableStateOf(CoverMotif.GLOW) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var hasCustom by remember { mutableStateOf(false) }

    val spec = CoverSpec(palette = palette, title = title, subtitle = subtitle, motif = motif)
    val savedMessage = stringResource(R.string.discovery_cover_saved)
    val saveFailedMessage = stringResource(R.string.discovery_cover_save_failed)
    val removedMessage = stringResource(R.string.discovery_cover_removed)

    // The preview is re-drawn on the device whenever the spec changes.
    LaunchedEffect(spec) {
        preview = withContext(Dispatchers.Default) {
            runCatching { renderCover(spec, sizePx = 512) }.getOrNull()
        }
    }
    LaunchedEffect(playlistBrowseId) {
        hasCustom = withContext(Dispatchers.IO) {
            PlaylistCovers.coverFile(playlistBrowseId) != null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.discovery_back),
                    )
                }
                Text(
                    text = stringResource(R.string.discovery_cover_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                if (hasCustom) {
                    IconButton(onClick = {
                        PlaylistCovers.remove(playlistBrowseId)
                        hasCustom = false
                        onSaved()
                        scope.launch { snackbar.showSnackbar(removedMessage) }
                    }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.discovery_cover_remove),
                        )
                    }
                }
            }

            val bitmap = preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp)),
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            Spacer(Modifier.height(20.dp))

            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discovery_cover_heading)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discovery_cover_subheading)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )

                Text(
                    text = stringResource(R.string.discovery_cover_palette),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                PaletteRow(
                    palettes = COVER_PALETTES,
                    selected = palette,
                    onSelect = { palette = it },
                )

                Text(
                    text = stringResource(R.string.discovery_cover_style),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MotifChip(CoverMotif.GLOW, motif, R.string.discovery_cover_style_gradient) {
                        motif = it
                    }
                    MotifChip(CoverMotif.WAVES, motif, R.string.discovery_cover_style_waves) {
                        motif = it
                    }
                    MotifChip(CoverMotif.RINGS, motif, R.string.discovery_cover_style_rings) {
                        motif = it
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            val full = withContext(Dispatchers.Default) {
                                runCatching { renderCover(spec) }.getOrNull()
                            }
                            val saved = if (full != null) {
                                PlaylistCovers.save(playlistBrowseId, full)
                            } else {
                                null
                            }
                            saving = false
                            if (saved != null) {
                                hasCustom = true
                                onSaved()
                                snackbar.showSnackbar(savedMessage)
                            } else {
                                snackbar.showSnackbar(saveFailedMessage)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.discovery_cover_save))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteRow(
    palettes: List<CoverPalette>,
    selected: CoverPalette,
    onSelect: (CoverPalette) -> Unit,
) {
    // A flow rather than a row: eight swatches do not fit a narrow phone in
    // one line, and a horizontal scroll would hide half the choices.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        palettes.forEach { entry ->
            val isSelected = entry == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(entry.start), Color(entry.end)),
                        ),
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun MotifChip(
    value: CoverMotif,
    selected: CoverMotif,
    labelRes: Int,
    onSelect: (CoverMotif) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}
