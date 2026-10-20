package com.music.sonariq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.mood.Mood
import com.music.sonariq.playback.AutoplaySeeds
import com.music.sonariq.playback.launchSeededAutoplay
import com.music.sonariq.playback.rememberMediaController
import com.music.sonariq.ui.icons.SonariqIcons
import kotlinx.coroutines.launch

/**
 * The "Mood stations" chip row on Home: Chill / Party / Focus / Workout.
 *
 * Tapping a chip asks the caller to build that mood's station from the
 * listener's own history; [buildingMood] drives the loading state — the tapped
 * chip spins while the others wait, plus a progress line underneath — and the
 * row stays inert until the build finishes.
 *
 * Holding a chip opens its menu: "Start Autoplay" builds the same station
 * right here and starts it with the AutoPlay setting on, so the music keeps
 * going from the playing tracks.
 */
@Composable
fun MoodStationsRow(
    buildingMood: Mood?,
    onMoodClick: (Mood) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberMediaController()
    // Which chip's menu is open, and which chip is building its autoplay
    // queue — both live here because the tap path's [buildingMood] belongs
    // to the caller and must not be borrowed for this.
    var autoplayMenuFor by remember { mutableStateOf<Mood?>(null) }
    var autoplayBuilding by remember { mutableStateOf<Mood?>(null) }
    val startMoodAutoplay: (Mood) -> Unit = { mood ->
        scope.launch {
            autoplayBuilding = mood
            context.launchSeededAutoplay(controller) {
                AutoplaySeeds.moodSeeds(context, mood)
            }
            autoplayBuilding = null
        }
    }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.mood_stations),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Mood.entries, key = { it.name }) { mood ->
                val active = buildingMood == mood || autoplayBuilding == mood
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) colors.primary else colors.surfaceVariant)
                            .combinedClickable(
                                enabled = buildingMood == null && autoplayBuilding == null,
                                onClick = { onMoodClick(mood) },
                                onLongClick = { autoplayMenuFor = mood },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary,
                            )
                        }
                        Text(
                            text = stringResource(mood.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = autoplayMenuFor == mood,
                        onDismissRequest = { autoplayMenuFor = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.start_autoplay)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = SonariqIcons.Infinity,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                autoplayMenuFor = null
                                startMoodAutoplay(mood)
                            },
                        )
                    }
                }
            }
        }
        if (buildingMood != null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
                color = colors.primary,
            )
            Text(
                text = stringResource(R.string.mood_building),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
            )
        }
    }
}
