package com.music.sonariq.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.sonariq.SonariqApplication
import com.music.sonariq.data.CrashRecorder
import com.music.sonariq.data.admin.AdminStats
import com.music.sonariq.data.admin.AdminStore
import com.music.sonariq.data.admin.OwnerRecord
import com.music.sonariq.data.library.StorageManager
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.lyrics.LyricsSource
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.data.stats.ReplaySummary
import com.music.sonariq.download.Downloads
import com.music.sonariq.playback.AutoplayHistoryStore
import com.music.sonariq.playback.AutoplayPickDiagnostics
import kotlinx.coroutines.launch

/**
 * The Admin Control Center's sections. Every number here is read from the
 * store that owns it at the moment the section opens; every control writes
 * through the same settings the rest of the app uses, so nothing in this
 * panel is a decorative duplicate. Where a server would be needed, the
 * section says so instead of inventing data.
 */
@Composable
internal fun AdminSectionBody(section: AdminSection, owner: OwnerRecord, modifier: Modifier = Modifier) {
    when (section) {
        AdminSection.Overview -> AdminOverviewSection(owner, modifier)
        AdminSection.Users -> AdminUsersSection(modifier)
        AdminSection.Music -> AdminMusicSection(modifier)
        AdminSection.Autoplay -> AdminAutoplaySection(modifier)
        AdminSection.Lyrics -> AdminLyricsSection(modifier)
        AdminSection.Analytics -> AdminAnalyticsSection(modifier)
        AdminSection.Health -> AdminHealthSection(modifier)
        AdminSection.Announcements -> AdminAnnouncementsSection(modifier)
        AdminSection.Config -> AdminConfigSection(modifier)
        AdminSection.Security -> AdminSecuritySection(owner, modifier)
        AdminSection.Audit -> AdminAuditSection(modifier)
    }
}

@Composable
private fun AdminUsersSection(modifier: Modifier = Modifier) {
    val sessions = remember {
        runCatching { SonariqApplication.authStore.sessions }.getOrDefault(emptyList())
    }
    val active = remember {
        runCatching { SonariqApplication.authStore.activeSession }.getOrNull()
    }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Accounts on this device") {
                AdminStatRow("Connected external accounts", "${sessions.size}")
                val profile = active?.profiles?.firstOrNull { it.profileId == active.activeProfileId }
                    ?: active?.profiles?.firstOrNull()
                AdminStatRow("Active profile", profile?.name ?: "None")
                AdminNote(
                    "These are this device's own signed-in sessions, managed in " +
                        "Settings, Account & scrobbling. Passwords and session " +
                        "tokens are never shown here.",
                )
            }
        }
        item {
            AdminInfoCard("User directory") {
                AdminNote(
                    "Unavailable: Sonariq has no account server and no user " +
                        "database, so there is no directory of other users to " +
                        "search, suspend or delete. Listening data exists only " +
                        "on each listener's own device.",
                )
            }
        }
    }
}

@Composable
private fun AdminMusicSection(modifier: Modifier = Modifier) {
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    val downloads by Downloads.saved.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        summary = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
    }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("On this device") {
                AdminStatRow("Downloaded tracks", "${downloads.size}")
                val current = summary
                if (current != null) {
                    AdminStatRow("Tracks played", "${current.distinctSongs}")
                    AdminStatRow("Artists played", "${current.distinctArtists}")
                }
            }
        }
        val current = summary
        if (current == null) {
            item { AdminLoading() }
        } else if (current.isEmpty) {
            item {
                AdminInfoCard("Most played") {
                    AdminNote("Nothing played yet on this device.")
                }
            }
        } else {
            item {
                AdminInfoCard("Most played tracks") {
                    current.songs.take(10).forEach { ranked ->
                        AdminStatRow(
                            "${ranked.song.title} — ${ranked.song.artist}",
                            "${ranked.plays} plays • ${formatAdminDuration(ranked.ms)}",
                        )
                    }
                }
            }
            item {
                AdminInfoCard("Most played artists") {
                    current.artists.take(10).forEach { ranked ->
                        AdminStatRow(ranked.title, "${ranked.plays} plays • ${formatAdminDuration(ranked.ms)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAutoplaySection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val exploration by AppSettings.autoplayExploration.collectAsStateWithLifecycle()
    val mixLibrary by AppSettings.autoplayMixLibrary.collectAsStateWithLifecycle()
    val mixDownloads by AppSettings.autoplayMixDownloads.collectAsStateWithLifecycle()
    val mixDiscovery by AppSettings.autoplayMixDiscovery.collectAsStateWithLifecycle()
    val discoveryMode by AppSettings.autoplayDiscoveryMode.collectAsStateWithLifecycle()
    val downloadsOnly by AppSettings.autoplayDownloadsOnly.collectAsStateWithLifecycle()
    val bannedSongs by AppSettings.autoplayBannedSongIds.collectAsStateWithLifecycle()
    val bannedArtists by AppSettings.autoplayBannedArtists.collectAsStateWithLifecycle()
    val seeds = remember { AutoplayHistoryStore.seedCount(context) }
    val batches = remember { AutoplayHistoryStore.batchCount(context) }
    var confirmClearBanned by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Engine") {
                AdminToggleRow("Smart Autoplay", autoplay) { value ->
                    AppSettings.setAutoplay(value)
                    AdminStore.recordAudit(context, "config.change", "autoplay", if (value) "on" else "off")
                }
                AdminStatRow("Seeds with freshness history", "$seeds")
                AdminStatRow("Remembered batches", "$batches")
                AdminStatRow(
                    "Last batch match",
                    when {
                        !AutoplayPickDiagnostics.hasRecord -> "—"
                        AutoplayPickDiagnostics.lastBatchLowConfidence ->
                            "Best available (low confidence)"
                        else -> "Scored fit"
                    },
                )
                AdminNote(
                    "Within a session, songs already played, skipped, queued or " +
                        "suggested are not offered again while alternatives exist — " +
                        "the session guard in Settings, on by default. Across " +
                        "sessions, this freshness history stops a repeated seed " +
                        "from serving the same batch again: earlier picks are " +
                        "demoted, never banned. Discovery picks are also scored " +
                        "against the playing song's measured energy, tempo, key " +
                        "and vocals plus your listening history: poor fits are " +
                        "held back while alternatives exist, and a batch that " +
                        "had to settle says so above.",
                )
            }
        }
        item {
            AdminInfoCard("Discovery intensity") {
                val names = listOf("Safe", "Balanced", "Adventurous")
                AdminStatRow("Current", names.getOrElse(exploration) { "Balanced" })
                names.forEachIndexed { index, name ->
                    OutlinedButton(
                        onClick = {
                            AppSettings.setAutoplayExploration(index)
                            AdminStore.recordAudit(context, "config.change", "autoplay intensity", name)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) { Text(if (index == exploration) "$name (current)" else name) }
                }
            }
        }
        item {
            AdminInfoCard("Source mix") {
                AdminSliderRow("Library", mixLibrary) { AppSettings.setAutoplayMixLibrary(it) }
                AdminSliderRow("Downloads", mixDownloads) { AppSettings.setAutoplayMixDownloads(it) }
                AdminSliderRow("Discovery", mixDiscovery) { AppSettings.setAutoplayMixDiscovery(it) }
                AdminNote("Relative weights, exactly as the autoplay engine reads them.")
            }
        }
        item {
            AdminInfoCard("Modes and exclusions") {
                AdminToggleRow("Discovery mode (unheard songs first)", discoveryMode) { value ->
                    AppSettings.setAutoplayDiscoveryMode(value)
                    AdminStore.recordAudit(context, "config.change", "autoplay discovery mode", if (value) "on" else "off")
                }
                AdminToggleRow("Downloads only", downloadsOnly) { value ->
                    AppSettings.setAutoplayDownloadsOnly(value)
                    AdminStore.recordAudit(context, "config.change", "autoplay downloads only", if (value) "on" else "off")
                }
                AdminStatRow("Excluded songs", "${bannedSongs.size}")
                AdminStatRow("Excluded artists", "${bannedArtists.size}")
                if (bannedSongs.isNotEmpty() || bannedArtists.isNotEmpty()) {
                    OutlinedButton(onClick = { confirmClearBanned = true }) {
                        Text("Clear all exclusions")
                    }
                }
            }
        }
    }

    if (confirmClearBanned) {
        AlertDialog(
            onDismissRequest = { confirmClearBanned = false },
            title = { Text("Clear autoplay exclusions?") },
            text = { Text("Songs and artists excluded from a song's menu become eligible for autoplay again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppSettings.setAutoplayBannedSongIds(emptySet())
                        AppSettings.setAutoplayBannedArtists(emptySet())
                        AdminStore.recordAudit(context, "autoplay.exclusions", "all", "cleared")
                        confirmClearBanned = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearBanned = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AdminLyricsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabledSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<AdminStats.LyricsCacheStats?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearedNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        stats = runCatching { AdminStats.lyricsCacheStats() }.getOrNull()
    }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Cache on this device") {
                val current = stats
                if (current == null) {
                    AdminNote("Measuring…")
                } else {
                    AdminStatRow("Cached lyrics", "${current.entries}")
                    AdminStatRow("Word-synced (provider)", "${current.wordSynced}")
                    AdminStatRow("Word-synced (AI measured)", "${current.aiMeasured}")
                    AdminStatRow("Line-synced or plain", "${current.lineOnly}")
                    AdminStatRow("Unclassified", "${current.unclassified}")
                    AdminStatRow("Remembered misses", "${current.misses}")
                    AdminStatRow("Cache size", StorageManager.formatBytes(current.bytes))
                    AdminNote(
                        "Misses are retried by the engine on later plays; nothing " +
                            "here is stuck. Clearing the cache makes the next play " +
                            "of each song fetch its lyrics again.",
                    )
                    clearedNote?.let { AdminNote(it) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmClear = true }) {
                        Text("Clear lyrics cache")
                    }
                }
            }
        }
        item {
            AdminInfoCard("Providers") {
                LyricsSource.entries.filterNot { it.hidden }.forEach { source ->
                    AdminStatRow(
                        source.label,
                        when {
                            source !in enabledSources -> "Off"
                            source.wordSynced -> "On • word timings"
                            else -> "On • line timings"
                        },
                    )
                }
                AdminNote("Which providers are on is chosen in Settings, Lyrics.")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the lyrics cache?") },
            text = { Text("Cached lyrics and remembered misses on this device are deleted. Songs fetch fresh lyrics the next time they play.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            val removed = AdminStats.clearLyricsCache()
                            AdminStore.recordAudit(context, "lyrics.cache", "all entries", "cleared $removed files")
                            clearedNote = "Cleared $removed cache files."
                            stats = AdminStats.lyricsCacheStats()
                        }
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AdminAnalyticsSection(modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf<ReplaySummary?>(null) }
    var allTime by remember { mutableStateOf<ReplaySummary?>(null) }
    LaunchedEffect(Unit) {
        month = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
        allTime = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
    }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("This month") {
                val current = month
                when {
                    current == null -> AdminNote("Measuring…")
                    current.isEmpty -> AdminNote("Nothing played yet this month.")
                    else -> {
                        AdminStatRow("Plays", "${current.totalPlays}")
                        AdminStatRow("Listening time", formatAdminDuration(current.totalMs))
                        AdminStatRow("Different songs", "${current.distinctSongs}")
                        current.peakHour?.let {
                            AdminStatRow("Peak hour", it.toString().padStart(2, '0') + ":00")
                        }
                    }
                }
            }
        }
        val currentAll = allTime
        if (currentAll != null && !currentAll.isEmpty) {
            item {
                AdminInfoCard("Top genres (all time)") {
                    if (currentAll.genres.isEmpty()) {
                        AdminNote("No genre data recorded yet.")
                    } else {
                        currentAll.genres.take(5).forEach { genre ->
                            AdminStatRow(genre.title, "${genre.plays} plays • ${formatAdminDuration(genre.ms)}")
                        }
                    }
                }
            }
            item {
                AdminInfoCard("Listening by hour (all time)") {
                    val topHours = currentAll.hourOfDay.withIndex()
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .take(3)
                    if (topHours.isEmpty()) {
                        AdminNote("No hourly data recorded yet.")
                    } else {
                        topHours.forEach { (hour, ms) ->
                            AdminStatRow(hour.toString().padStart(2, '0') + ":00", formatAdminDuration(ms))
                        }
                    }
                }
            }
        }
        item {
            AdminInfoCard("Scope") {
                AdminNote(
                    "Every figure here is this device's own listening, recorded " +
                        "locally. Cross-user analytics (daily or monthly active " +
                        "users, registration trends) need a server Sonariq does " +
                        "not run, so they are not shown rather than invented.",
                )
            }
        }
    }
}

@Composable
private fun AdminHealthSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var server by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf<StorageManager.Usage?>(null) }
    var crash by remember { mutableStateOf<CrashRecorder.Entry?>(null) }
    var crashChecked by remember { mutableStateOf(false) }
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val probe = runCatching {
            ListenTogether.probeHealthWithLatency(ListenTogether.defaultServer, 10_000L)
        }.getOrNull()
        server = when {
            probe == null -> "Unknown"
            probe.isOnline -> "Healthy (${probe.latencyMs} ms)"
            else -> "Offline"
        }
        usage = runCatching { StorageManager.measure(context) }.getOrNull()
        crash = runCatching { CrashRecorder.latest(context) }.getOrNull()
        crashChecked = true
    }
    val device = remember { AdminStats.deviceStorage(context) }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Services") {
                AdminStatRow("Listen Together backend", server ?: "Checking…")
                AdminStatRow("Offline mode", if (offlineMode) "On" else "Off")
                AdminNote(
                    "Lyrics and music providers are exercised on every lookup; " +
                        "their per-provider state lives in the Lyrics section.",
                )
            }
        }
        item {
            AdminInfoCard("App stability") {
                val recorded = crash
                when {
                    !crashChecked -> AdminNote("Checking…")
                    recorded == null -> AdminNote(
                        "No crashes recorded on this device. When the app " +
                            "dies unexpectedly, the exception is written " +
                            "here — on this device only, never sent anywhere.",
                    )
                    else -> {
                        AdminStatRow("Last crash", CrashRecorder.formatTime(recorded.timestampMs))
                        AdminStatRow("Type", recorded.exceptionType)
                        recorded.traceLines.take(8).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                CrashRecorder.clear(context)
                                crash = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Clear recorded crashes") }
                    }
                }
            }
        }
        item {
            AdminInfoCard("Storage") {
                val current = usage
                if (current == null) {
                    AdminNote("Measuring…")
                } else {
                    AdminStatRow("Downloads", StorageManager.formatBytes(current.downloadsBytes))
                    AdminStatRow("Audio cache", StorageManager.formatBytes(current.audioCacheBytes))
                    AdminStatRow("Image cache", StorageManager.formatBytes(current.imageCacheBytes))
                    AdminStatRow("Artwork", StorageManager.formatBytes(current.artworkBytes))
                }
                AdminStatRow("Device free space", StorageManager.formatBytes(device.freeBytes))
                AdminStatRow("Device total space", StorageManager.formatBytes(device.totalBytes))
            }
        }
    }
}

@Composable
private fun AdminAnnouncementsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var announcements by remember { mutableStateOf(AdminStore.announcements(context)) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var confirmPublish by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val now = remember { System.currentTimeMillis() }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Publish an announcement") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { confirmPublish = true },
                    enabled = title.isNotBlank() && body.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Publish") }
                AdminNote(
                    "Announcements are stored on this device. Sonariq has no " +
                        "server to deliver them to other listeners, so publishing " +
                        "here keeps the notice and its history on this device only.",
                )
            }
        }
        if (announcements.isEmpty()) {
            item {
                AdminInfoCard("History") { AdminNote("No announcements yet.") }
            }
        } else {
            items(announcements, key = { it.id }) { announcement ->
                AdminInfoCard(announcement.title) {
                    AdminNote(announcement.body)
                    AdminStatRow("Published", formatAdminDateTime(announcement.createdAtMs))
                    val expiresAt = announcement.expiresAtMs
                    AdminStatRow(
                        "Status",
                        when {
                            expiresAt == null -> "Live (no expiry)"
                            announcement.isLive(now) -> "Live until ${formatAdminDate(expiresAt)}"
                            else -> "Expired ${formatAdminDate(expiresAt)}"
                        },
                    )
                    OutlinedButton(onClick = { deleteId = announcement.id }) { Text("Delete") }
                }
            }
        }
    }

    if (confirmPublish) {
        AlertDialog(
            onDismissRequest = { confirmPublish = false },
            title = { Text("Publish this announcement?") },
            text = { Text("It is saved to this device's announcement history and written to the audit log.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AdminStore.publishAnnouncement(context, title, body, expiresAtMs = null)
                        announcements = AdminStore.announcements(context)
                        title = ""
                        body = ""
                        confirmPublish = false
                    },
                ) { Text("Publish") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPublish = false }) { Text("Cancel") }
            },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete this announcement?") },
            text = { Text("The deletion is written to the audit log.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AdminStore.deleteAnnouncement(context, id)
                        announcements = AdminStore.announcements(context)
                        deleteId = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AdminConfigSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsPrefetch by AppSettings.lyricsPrefetch.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Features") {
                AdminToggleRow("Smart Autoplay", autoplay) { value ->
                    AppSettings.setAutoplay(value)
                    AdminStore.recordAudit(context, "config.change", "autoplay", if (value) "on" else "off")
                }
                AdminToggleRow("Synced lyrics", syncedLyrics) { value ->
                    AppSettings.setSyncedLyrics(value)
                    AdminStore.recordAudit(context, "config.change", "synced lyrics", if (value) "on" else "off")
                }
                AdminToggleRow("Lyrics prefetch", lyricsPrefetch) { value ->
                    AppSettings.setLyricsPrefetch(value)
                    AdminStore.recordAudit(context, "config.change", "lyrics prefetch", if (value) "on" else "off")
                }
                AdminToggleRow("Offline mode", offlineMode) { value ->
                    AppSettings.setOfflineMode(value)
                    AdminStore.recordAudit(context, "config.change", "offline mode", if (value) "on" else "off")
                }
                AdminNote(
                    "These are the app's real switches, changed through the same " +
                        "settings store the rest of Sonariq reads. Ownership " +
                        "itself is never editable here.",
                )
            }
        }
    }
}

@Composable
private fun AdminSecuritySection(owner: OwnerRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions = remember {
        runCatching { SonariqApplication.authStore.sessions }.getOrDefault(emptyList())
    }
    val auditCount = remember { AdminStore.audit(context).size }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Ownership") {
                AdminStatRow("Owner label", owner.label)
                AdminStatRow("Owner ID", owner.id)
                AdminStatRow("Claimed", formatAdminDateTime(owner.claimedAtMs))
                AdminStatRow("Second claim possible", "No — the record is written once")
                AdminStatRow("Ownership controls in this panel", "None — it cannot be edited or removed here")
            }
        }
        item {
            AdminInfoCard("Sessions") {
                AdminStatRow("Connected external accounts", "${sessions.size}")
                AdminStatRow("Audit entries recorded", "$auditCount")
                AdminNote(
                    "Session sign-out lives in Settings, Account & scrobbling. " +
                        "No passwords, tokens or keys are stored in the admin " +
                        "record or the audit log, and none are shown here.",
                )
            }
        }
        item {
            AdminInfoCard("What protects this panel") {
                AdminNote(
                    "Honestly stated: ownership is a one-time claim stored in " +
                        "this app's private storage on this device, checked by " +
                        "the app itself. That is sound against ordinary use, " +
                        "but it is not server-verified — a modified app or a " +
                        "rooted device could bypass it. Sonariq runs no backend " +
                        "that could hold the record instead, so the panel does " +
                        "not claim stronger security than it has.",
                )
            }
        }
    }
}

@Composable
private fun AdminAuditSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries = remember { AdminStore.audit(context) }
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entries.isEmpty()) {
            item {
                AdminInfoCard("Audit log") { AdminNote("No administrative actions recorded yet.") }
            }
        } else {
            items(entries) { entry ->
                AdminInfoCard(entry.action) {
                    AdminStatRow("Target", entry.target)
                    AdminStatRow("Result", entry.result)
                    AdminStatRow("When", formatAdminDateTime(entry.atMs))
                }
            }
        }
    }
}

@Composable
internal fun AdminToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun AdminSliderRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Text("$label: $value")
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = 0f..100f,
        steps = 19,
    )
}
