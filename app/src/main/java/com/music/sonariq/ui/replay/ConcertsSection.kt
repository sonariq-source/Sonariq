package com.music.sonariq.ui.replay

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.R
import com.music.sonariq.data.concerts.ConcertEvent
import com.music.sonariq.data.concerts.ConcertsRepository
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.ui.components.PAGE_GUTTER
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The Replay page's "Upcoming concerts" section: real shows by the
 * listener's top artists, from Ticketmaster's Discovery API.
 *
 * Drawn only while the toggle is on. With no API key the section says what
 * is missing (and where to fix it) rather than pretending there are no
 * concerts — an empty list here must mean "none found", never "not looked".
 */
@Composable
fun ConcertsSection(
    dark: Boolean,
    onOpenArtist: (String?, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by AppSettings.concertAlertsEnabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val apiKey by AppSettings.ticketmasterApiKey.collectAsStateWithLifecycle()
    val state by ConcertsRepository.state.collectAsStateWithLifecycle()

    val textColor = if (dark) Color.White else Color.Black
    val textVariant = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)

    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.upcoming_concerts),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W800,
                color = textColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 10.dp),
            )
            if (apiKey.isNotBlank()) {
                IconButton(
                    onClick = { scope.launch { ConcertsRepository.refresh(context) } },
                    enabled = !state.loading,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.concerts_check_now),
                        tint = textVariant,
                    )
                }
            }
        }

        when {
            apiKey.isBlank() -> {
                Text(
                    text = stringResource(R.string.concerts_no_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            state.loading && state.events.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = textColor.copy(alpha = 0.6f))
                }
            }
            state.events.isEmpty() -> {
                Text(
                    text = stringResource(
                        if (state.error) R.string.concerts_error else R.string.concerts_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            else -> {
                state.events.forEach { event ->
                    ConcertRow(
                        event = event,
                        textColor = textColor,
                        textVariant = textVariant,
                        onOpenArtist = { onOpenArtist(null, event.artistName) },
                        onTickets = {
                            event.ticketUrl?.let { url ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, url.toUri()),
                                    )
                                }
                            }
                        },
                    )
                }
                if (state.error) {
                    Text(
                        text = stringResource(R.string.concerts_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConcertRow(
    event: ConcertEvent,
    textColor: Color,
    textVariant: Color,
    onOpenArtist: () -> Unit,
    onTickets: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateBadge(event.localDate, textColor, textVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.artistName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.W600,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onOpenArtist),
            )
            Text(
                text = listOfNotNull(
                    event.name.takeUnless { it.equals(event.artistName, ignoreCase = true) },
                    listOfNotNull(event.venue, event.city).joinToString(", ").takeIf { it.isNotEmpty() },
                ).joinToString(" · ").takeIf { it.isNotEmpty() }
                    ?: formatConcertDate(event.localDate, event.localTime),
                style = MaterialTheme.typography.bodyMedium,
                color = textVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event.ticketUrl != null) {
            TextButton(onClick = onTickets) {
                Icon(
                    imageVector = Icons.Rounded.ConfirmationNumber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.concerts_tickets))
            }
        }
    }
}

@Composable
private fun DateBadge(localDate: String?, textColor: Color, textVariant: Color) {
    val parsed = runCatching { localDate?.let { LocalDate.parse(it) } }.getOrNull()
    Column(
        modifier = Modifier.width(46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = parsed?.dayOfMonth?.toString() ?: "–",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = parsed?.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
                ?.uppercase(Locale.getDefault()) ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = textVariant,
        )
    }
}

private fun formatConcertDate(localDate: String?, localTime: String?): String {
    val date = runCatching { localDate?.let { LocalDate.parse(it) } }.getOrNull()
        ?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        ?: localDate.orEmpty()
    return if (!localTime.isNullOrBlank()) "$date · $localTime" else date
}
