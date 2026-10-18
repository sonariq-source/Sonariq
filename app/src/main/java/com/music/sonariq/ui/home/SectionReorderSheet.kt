package com.music.sonariq.ui.home

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.model.HomeShelf

/**
 * The manual side of the learning home tab: a sheet listing the home feed's
 * sections with drag handles, so the listener can pin their own order. Dragging
 * here writes [HomeSectionOrderStore], which [HomeSectionRanking.rankSections]
 * reads — a dragged section always outranks anything the behaviour ranking
 * would put first.
 *
 * The drag mechanics follow the codebase's established handle-drag pattern
 * (see the source-priority list): the gesture keeps only the travelled
 * distance and the starting slot, and both the row's drawn position and its
 * slot are derived from those two numbers, so they cannot drift apart however
 * many swaps happen along the way. The order is written back on drop, not per
 * frame.
 *
 * Phase 2 shows this sheet: from the "Reorder home sections" row the phase
 * adds to Settings → Appearance, and/or from a drag affordance injected into
 * the home shelf headers. `shelves` is the feed's current sections in feed
 * order — the ranking's manual ids are matched against them, so a section the
 * feed dropped simply doesn't appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionReorderSheet(
    shelves: List<HomeShelf>,
    onDismiss: () -> Unit,
) {
    val manualOrder by HomeSectionOrderStore.order.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The sheet shows the sections in their *effective* order — manual first,
    // then the rest in feed order — so dragging starts from what Home shows.
    var liveOrder by remember(shelves, manualOrder) {
        mutableStateOf(
            HomeSectionRanking.rankSections(
                shelves = shelves,
                recentArtists = emptySet(),
                manualOrder = manualOrder,
                learn = false,
            ).map(HomeSectionRanking::sectionId),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ux_home_reorder_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    HomeSectionOrderStore.reset()
                    liveOrder = shelves.map(HomeSectionRanking::sectionId)
                }) {
                    Text(stringResource(R.string.ux_home_reset_order))
                }
            }
            Text(
                text = stringResource(R.string.ux_home_drag_to_reorder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        var dragged by remember { mutableStateOf<String?>(null) }
        var totalDrag by remember { mutableFloatStateOf(0f) }
        var startIndex by remember { mutableIntStateOf(0) }
        var pitchPx by remember { mutableFloatStateOf(0f) }
        var lockedPitchPx by remember { mutableFloatStateOf(0f) }

        val titles = remember(shelves) { shelves.associateBy(HomeSectionRanking::sectionId) }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            itemsIndexed(
                items = liveOrder,
                key = { _, id -> id },
            ) { _, id ->
                val shelf = titles[id]
                key(id) {
                    val isDragging = id == dragged
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .onSizeChanged { pitchPx = it.height.toFloat() }
                            .graphicsLayer {
                                translationY = if (isDragging) {
                                    totalDrag -
                                        (liveOrder.indexOf(id) - startIndex) * lockedPitchPx
                                } else {
                                    0f
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = shelf?.title ?: id,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
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
                                            dragged = id
                                            totalDrag = 0f
                                            startIndex = liveOrder.indexOf(id)
                                            lockedPitchPx = pitchPx
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            val pitch = lockedPitchPx
                                            if (pitch <= 0f) return@detectDragGestures
                                            var at = liveOrder.indexOf(id)
                                            if (at < 0) return@detectDragGestures
                                            totalDrag = (totalDrag + delta.y).coerceIn(
                                                -startIndex * pitch,
                                                (liveOrder.lastIndex - startIndex) * pitch,
                                            )
                                            while (true) {
                                                val travelled = totalDrag / pitch
                                                val moved = (at - startIndex).toFloat()
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
                                            HomeSectionOrderStore.save(liveOrder)
                                        },
                                        onDragCancel = {
                                            dragged = null
                                            liveOrder = HomeSectionOrderStore.order.value.ifEmpty {
                                                shelves.map(HomeSectionRanking::sectionId)
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * The drag affordance the feature adds to home shelf headers: a grip the
 * listener holds to open the reorder sheet. Drawn small and quiet on purpose —
 * the home screen's layout is otherwise untouched, per the ordering-only rule.
 *
 * Phase 2 places this at the trailing end of each shelf header in
 * [HomeScreen][com.music.sonariq.ui.screens.HomeScreen]'s shelf rows, with
 * `onOpenReorder` showing [SectionReorderSheet].
 */
@Composable
fun HomeShelfDragAffordance(
    onOpenReorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onOpenReorder,
        modifier = modifier.size(36.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = stringResource(R.string.ux_home_reorder_title),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}
