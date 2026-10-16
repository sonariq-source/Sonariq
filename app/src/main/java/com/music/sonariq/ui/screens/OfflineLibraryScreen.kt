package com.music.sonariq.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.model.Song
import com.music.sonariq.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Library tab becomes while [com.music.sonariq.data.settings.AppSettings.offlineMode]
 * is on: the Downloads folder alone — every track on it verified on the
 * device, nothing that would need the network to open.
 *
 * Built on [LocalMusicScreen] with `isDownloads = true`, so it is the same
 * folder view the user already knows (tabs, sorting, collections, delete),
 * just promoted to the whole tab. Reloads whenever the download record
 * changes, so a finished or deleted download shows up without reopening.
 */
@Composable
fun OfflineLibraryScreen(
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    /**
     * An album/playlist grouping held — with the id it is recorded under in
     * [Downloads.collections] when it was downloaded whole, so the menu can
     * offer the collection's own actions, exactly like the Downloads folder
     * page does.
     */
    onCollectionLongPress: (String, List<Song>, String?) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onDeleteDownloads: (List<Song>) -> Unit,
    onUploadToWebDav: ((List<Song>) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val songs by produceState<List<Song>?>(null, saved) {
        value = withContext(Dispatchers.IO) {
            runCatching { Downloads.getDownloadedSongs(context) }.getOrDefault(emptyList())
        }
    }
    val loaded = songs
    if (loaded == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(64.dp),
            )
        }
        return
    }
    val collections = remember(loaded) {
        Downloads.collectionsAmong(loaded)
    }
    LocalMusicScreen(
        songs = loaded,
        onSongClick = onSongClick,
        onSongLongPress = onSongLongPress,
        onSongSwipe = onSongSwipe,
        onShuffle = onShuffle,
        contentPadding = contentPadding,
        // Honest about the one reason this view can be empty: nothing has
        // been downloaded yet, and offline mode refuses to offer the network
        // as an alternative.
        emptyMessage = stringResource(R.string.offline_library_empty),
        onCollectionLongPress = { label, grouped ->
            val downloadId = collections.firstOrNull {
                it.title == label && it.songs == grouped
            }?.id
            onCollectionLongPress(label, grouped, downloadId)
        },
        collections = collections,
        isDownloads = true,
        currentSong = currentSong,
        isPlaying = isPlaying,
        onDeleteDownloads = onDeleteDownloads,
        onUploadToWebDav = onUploadToWebDav,
        modifier = modifier,
    )
}
