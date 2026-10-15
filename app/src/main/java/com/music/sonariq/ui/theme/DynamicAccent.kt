package com.music.sonariq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.sonariq.data.model.Song
import com.music.sonariq.data.remote.RemoteArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The artwork-driven accent ARGB for the dynamic theme, derived from [song]'s
 * cover via [dominantColor].
 *
 * Returns null when [enabled] is false, when nothing is playing, or when the
 * artwork can't be loaded or decoded — callers fall back to the user's chosen
 * accent in all of those cases, so the theme never depends on the network.
 *
 * Keyed on the track's videoId: one load per track, computed off the main
 * thread, and never re-fetched while the same song keeps playing.
 */
@Composable
fun rememberDynamicAccentArgb(song: Song?, enabled: Boolean): Int? {
    val context = LocalContext.current
    var accentArgb by remember { mutableStateOf<Int?>(null) }
    val videoId = song?.videoId
    LaunchedEffect(videoId, enabled) {
        if (!enabled || videoId == null) {
            accentArgb = null
            return@LaunchedEffect
        }
        val track = song
        accentArgb = withContext(Dispatchers.IO) {
            runCatching {
                val url = track?.thumbnailUrl
                    ?: track?.let {
                        RemoteArtworkStore.resolveArt(context.applicationContext, it)
                    }
                    ?: return@runCatching null
                val request = ImageRequest.Builder(context)
                    .data(url)
                    // Only the palette is needed — a thumbnail-sized decode
                    // keeps this cheap on data and memory.
                    .size(ARTWORK_SAMPLE_PX)
                    // Hardware bitmaps can't be read back into pixels.
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()
                    ?: return@runCatching null
                dominantColor(bitmap)
            }.getOrNull()
        }
    }
    return if (enabled) accentArgb else null
}

/** Decode size for palette extraction; the extractor downscales to 48px anyway. */
private const val ARTWORK_SAMPLE_PX = 256
