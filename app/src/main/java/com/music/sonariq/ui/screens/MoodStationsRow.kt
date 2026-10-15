package com.music.sonariq.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.mood.Mood

/**
 * The "Mood stations" chip row on Home: Chill / Party / Focus / Workout.
 *
 * Tapping a chip asks the caller to build that mood's station from the
 * listener's own history; [buildingMood] drives the loading state — the tapped
 * chip spins while the others wait, plus a progress line underneath — and the
 * row stays inert until the build finishes.
 */
@Composable
fun MoodStationsRow(
    buildingMood: Mood?,
    onMoodClick: (Mood) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
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
                val active = buildingMood == mood
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) colors.primary else colors.surfaceVariant)
                        .clickable(enabled = buildingMood == null) { onMoodClick(mood) }
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
