package com.music.sonariq.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.sonariq.R
import com.music.sonariq.data.model.QueueTier
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.QueueHistoryLog
import com.music.sonariq.playback.SavedQueueStore
import com.music.sonariq.playback.SavedQueues

/**
 * Queue 2.0's screen: one sheet with three tabs.
 *
 * - **Up next** — the current queue. Rows reorder by their drag handles and
 *   swipe away to remove; the currently playing track is pinned at the top.
 *   This is an additive manager screen: the existing inline queue UI is
 *   untouched, and every edit here goes through the same player callbacks it
 *   uses ([onJumpTo], [onMoveInQueue], [onRemoveFromQueue]).
 * - **Saved** — named queues, persisted in [SavedQueueStore]. Save the current
 *   queue, play one back, rename, delete, and reorder a saved queue's tracks.
 * - **History** — what the queue actually played this session, from
 *   [QueueHistoryLog]; tapping a row plays it again.
 *
 * Phase 2 shows this from a "Queues" button it adds to the player queue
 * header (next to the autoplay toggle in
 * [InlineQueue][com.music.sonariq.ui.player.InlineQueue]), passing the live
 * queue and the same callbacks the inline queue uses. [onPlayTracks] starts
 * playback of an arbitrary track list — the service already has the
 * play-a-list path the context menus use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueManagerSheet(
    queue: List<Song>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onPlayTracks: (List<Song>) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Mirrors the inline queue's drag-active callback so a Listen Together
     * party holds its queue publish until the row is dropped. No-op by
     * default; the inline queue passes the real one.
     */
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ux_queue_manage),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.ux_queue_tab_up_next)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.ux_queue_tab_saved)) },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(stringResource(R.string.ux_queue_tab_history)) },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
            ) {
                when (tab) {
                    0 -> UpNextTab(
                        queue = queue,
                        currentIndex = currentIndex,
                        onJumpTo = onJumpTo,
                        onMoveInQueue = onMoveInQueue,
                        onRemoveFromQueue = onRemoveFromQueue,
                        onDragActiveChange = onDragActiveChange,
                    )
                    1 -> SavedTab(onPlayTracks = onPlayTracks)
                    2 -> HistoryTab(onPlayTracks = onPlayTracks)
                }
            }
        }
    }
}

// ── Up next ─────────────────────────────────────────────────────────────────

@Composable
private fun UpNextTab(
    queue: List<Song>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    if (showSaveDialog) {
        SaveQueueDialog(
            onSave = { name ->
                SavedQueueStore.saveQueue(name, queue)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ux_queue_tracks_count, queue.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ux_queue_save_current))
                }
            }
        }
        if (queue.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.ux_queue_empty_up_next),
                subtitle = "",
            )
            return
        }

        // Reorder bookkeeping lives here, not in the player: a local copy of the
        // queue order is dragged around, and the net move is committed to the
        // player once on drop — the same commit-on-release contract the source
        // priority list uses. Keys are stable across the gesture because the
        // drag never re-reads positions off the live player queue mid-flight.
        var liveOrder by remember(queue) {
            mutableStateOf(
                queue.mapIndexed { index, song ->
                    UpNextRow(
                        // Minted per queue snapshot: two copies of the same
                        // track need distinct drag identities.
                        key = java.util.UUID.randomUUID().toString(),
                        song = song,
                        queueIndex = index,
                    )
                },
            )
        }
        var dragged by remember { mutableStateOf<String?>(null) }
        var totalDrag by remember { mutableFloatStateOf(0f) }
        var startSlot by remember { mutableIntStateOf(0) }
        var pitchPx by remember { mutableFloatStateOf(0f) }
        var lockedPitchPx by remember { mutableFloatStateOf(0f) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(
                items = liveOrder,
                key = { _, row -> row.key },
            ) { _, row ->
                val song = row.song
                val isCurrent = row.queueIndex == currentIndex
                val reorderable = row.queueIndex > currentIndex
                key(row.key) {
                    val isDragging = row.key == dragged
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled && !isCurrent) {
                                onRemoveFromQueue(row.queueIndex)
                            }
                            false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = !isCurrent,
                        enableDismissFromEndToStart = !isCurrent,
                        backgroundContent = {},
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .onSizeChanged { pitchPx = it.height.toFloat() }
                                .graphicsLayer {
                                    translationY = if (isDragging) {
                                        totalDrag -
                                            (liveOrder.indexOfFirst { it.key == row.key } - startSlot) * lockedPitchPx
                                    } else {
                                        0f
                                    }
                                }
                                .clickable(enabled = !isCurrent) { onJumpTo(row.queueIndex) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (song.queueTier == QueueTier.AUTOPLAY) {
                                    // Autoplay picks live in the same list as
                                    // everything else here; without the mark
                                    // there is no telling which rows autoplay
                                    // chose.
                                    Text(
                                        text = stringResource(R.string.autoplay),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (reorderable) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = stringResource(R.string.ux_home_drag_to_reorder),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(44.dp)
                                        .padding(10.dp)
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragged = row.key
                                                    totalDrag = 0f
                                                    startSlot = liveOrder.indexOfFirst { it.key == row.key }
                                                    lockedPitchPx = pitchPx
                                                    onDragActiveChange(true)
                                                },
                                                onDrag = { change, delta ->
                                                    change.consume()
                                                    val pitch = lockedPitchPx
                                                    if (pitch <= 0f) return@detectDragGestures
                                                    var at = liveOrder.indexOfFirst { it.key == row.key }
                                                    if (at < 0) return@detectDragGestures
                                                    // The now-playing row and everything already
                                                    // played are pinned: a reorder never crosses them.
                                                    val topSlot = liveOrder.indexOfFirst {
                                                        it.queueIndex > currentIndex
                                                    }.coerceAtLeast(0)
                                                    totalDrag = (totalDrag + delta.y).coerceIn(
                                                        (topSlot - startSlot) * pitch,
                                                        (liveOrder.lastIndex - startSlot) * pitch,
                                                    )
                                                    while (true) {
                                                        val travelled = totalDrag / pitch
                                                        val moved = (at - startSlot).toFloat()
                                                        if (travelled > moved + 0.5f && at < liveOrder.lastIndex) {
                                                            liveOrder = liveOrder.toMutableList()
                                                                .apply { add(at + 1, removeAt(at)) }
                                                            at++
                                                        } else if (travelled < moved - 0.5f && at > topSlot) {
                                                            liveOrder = liveOrder.toMutableList()
                                                                .apply { add(at - 1, removeAt(at)) }
                                                            at--
                                                        } else {
                                                            break
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    dragged = null
                                                    onDragActiveChange(false)
                                                    val finalSlot = liveOrder.indexOfFirst { it.key == row.key }
                                                    // Net move committed once on drop: the row the
                                                    // drag started on, from its original queue slot
                                                    // to where it landed. (startSlot is where it
                                                    // started, finalSlot where it ended — not the
                                                    // other way round.)
                                                    if (finalSlot in liveOrder.indices && finalSlot != startSlot) {
                                                        onMoveInQueue(row.queueIndex, row.queueIndex + (finalSlot - startSlot))
                                                    }
                                                },
                                                onDragCancel = {
                                                    dragged = null
                                                    onDragActiveChange(false)
                                                    liveOrder = queue.mapIndexed { index, song ->
                                                        UpNextRow(
                                                            key = java.util.UUID.randomUUID().toString(),
                                                            song = song,
                                                            queueIndex = index,
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                )
                            } else {
                                Spacer(Modifier.width(44.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row of the Up next tab. [key] is the drag identity, stable for the
 * gesture; [queueIndex] is the row's position in the player's queue, which is
 * what the player callbacks speak.
 */
private data class UpNextRow(
    val key: String,
    val song: Song,
    val queueIndex: Int,
)

// ── Saved ───────────────────────────────────────────────────────────────────

@Composable
private fun SavedTab(onPlayTracks: (List<Song>) -> Unit) {
    val queues by SavedQueueStore.queues.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<SavedQueues.SavedQueue?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedQueues.SavedQueue?>(null) }
    var openQueue by remember { mutableStateOf<SavedQueues.SavedQueue?>(null) }

    if (renameTarget != null) {
        RenameQueueDialog(
            initial = renameTarget!!.name,
            onRename = { name ->
                SavedQueueStore.rename(renameTarget!!.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.ux_queue_delete_confirm, deleteTarget!!.name)) },
            confirmButton = {
                TextButton(onClick = {
                    SavedQueueStore.delete(deleteTarget!!.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.ux_queue_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.ux_queue_cancel))
                }
            },
        )
    }

    val open = openQueue
    if (open != null) {
        SavedQueueDetail(
            queue = queues.firstOrNull { it.id == open.id } ?: open,
            onPlayTracks = onPlayTracks,
            onBack = { openQueue = null },
        )
        return
    }

    if (queues.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.ux_queue_empty_saved),
            subtitle = stringResource(R.string.ux_queue_empty_saved_subtitle),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(queues, key = { _, q -> q.id }) { _, saved ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openQueue = saved }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = saved.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.ux_queue_tracks_count, saved.tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    onPlayTracks(saved.tracks.map { with(SavedQueues) { it.toSong() } })
                }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.ux_queue_load))
                }
                IconButton(onClick = { renameTarget = saved }) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.ux_queue_rename))
                }
                IconButton(onClick = { deleteTarget = saved }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.ux_queue_delete))
                }
            }
        }
    }
}

/** A saved queue's tracks, reorderable by drag handle — same gesture as Up next. */
@Composable
private fun SavedQueueDetail(
    queue: SavedQueues.SavedQueue,
    onPlayTracks: (List<Song>) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(
                text = queue.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = {
                onPlayTracks(queue.tracks.map { with(SavedQueues) { it.toSong() } })
            }) { Text(stringResource(R.string.ux_queue_load)) }
        }

        // Stable drag identities: a saved queue can hold the same track twice,
        // so neither the video id nor the slot is a safe key. The uuid is
        // minted once per queue load and travels with the track.
        var liveOrder by remember(queue.id, queue.tracks) {
            mutableStateOf(queue.tracks.map { SavedQueueRow(java.util.UUID.randomUUID().toString(), it) })
        }
        var dragged by remember { mutableStateOf<String?>(null) }
        var totalDrag by remember { mutableFloatStateOf(0f) }
        var startSlot by remember { mutableIntStateOf(0) }
        var pitchPx by remember { mutableFloatStateOf(0f) }
        var lockedPitchPx by remember { mutableFloatStateOf(0f) }

        fun persistOrder(rows: List<SavedQueueRow>) {
            liveOrder = rows
            SavedQueueStore.updateTracks(queue.id, rows.map { it.track })
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(liveOrder, key = { _, row -> row.key }) { index, row ->
                val track = row.track
                key(row.key) {
                    val isDragging = dragged == row.key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .onSizeChanged { pitchPx = it.height.toFloat() }
                            .graphicsLayer {
                                translationY = if (isDragging) {
                                    totalDrag -
                                        (liveOrder.indexOfFirst { it.key == row.key } - startSlot) * lockedPitchPx
                                } else {
                                    0f
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            persistOrder(liveOrder.filter { it.key != row.key })
                        }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.ux_queue_removed),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = stringResource(R.string.ux_home_drag_to_reorder),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(44.dp)
                                .padding(10.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragged = row.key
                                            totalDrag = 0f
                                            startSlot = liveOrder.indexOfFirst { it.key == row.key }
                                            lockedPitchPx = pitchPx
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            val pitch = lockedPitchPx
                                            if (pitch <= 0f) return@detectDragGestures
                                            var at = liveOrder.indexOfFirst { it.key == row.key }
                                            if (at < 0) return@detectDragGestures
                                            totalDrag = (totalDrag + delta.y).coerceIn(
                                                -startSlot * pitch,
                                                (liveOrder.lastIndex - startSlot) * pitch,
                                            )
                                            while (true) {
                                                val travelled = totalDrag / pitch
                                                val moved = (at - startSlot).toFloat()
                                                if (travelled > moved + 0.5f && at < liveOrder.lastIndex) {
                                                    liveOrder = liveOrder.toMutableList()
                                                        .apply { add(at + 1, removeAt(at)) }
                                                    at++
                                                } else if (travelled < moved - 0.5f && at > 0) {
                                                    liveOrder = liveOrder.toMutableList()
                                                        .apply { add(at - 1, removeAt(at)) }
                                                    at--
                                                } else {
                                                    break
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            dragged = null
                                            SavedQueueStore.updateTracks(queue.id, liveOrder.map { it.track })
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            liveOrder = queue.tracks.map {
                                                SavedQueueRow(java.util.UUID.randomUUID().toString(), it)
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** One row of a saved queue, with a drag identity stable across swaps. */
private data class SavedQueueRow(
    val key: String,
    val track: SavedQueues.QueueTrackSnapshot,
)

// ── History ─────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(onPlayTracks: (List<Song>) -> Unit) {
    val entries by QueueHistoryLog.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.ux_queue_empty_history),
            subtitle = stringResource(R.string.ux_queue_empty_history_subtitle),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(entries, key = { index, t -> "${t.videoId}:$index" }) { _, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onPlayTracks(listOf(with(SavedQueues) { track.toSong() }))
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = track.durationText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Dialogs & shared bits ───────────────────────────────────────────────────

@Composable
private fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ux_queue_save_current)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.ux_queue_save_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.ux_queue_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ux_queue_cancel)) }
        },
    )
}

@Composable
private fun RenameQueueDialog(
    initial: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ux_queue_rename)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.ux_queue_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ux_queue_cancel)) }
        },
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
