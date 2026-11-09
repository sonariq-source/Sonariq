package com.music.sonariq.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.sonariq.BuildConfig
import com.music.sonariq.data.admin.AdminStats
import com.music.sonariq.ui.components.Eyebrow
import com.music.sonariq.ui.components.SoundlineGlyph
import com.music.sonariq.ui.player.GlassTokens
import com.music.sonariq.ui.player.glassEdge
import com.music.sonariq.data.admin.AdminStore
import com.music.sonariq.data.admin.OwnerClaim
import com.music.sonariq.data.admin.OwnerRecord
import com.music.sonariq.data.library.StorageManager
import com.music.sonariq.data.listentogether.ListenTogether
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import com.music.sonariq.data.stats.ReplaySummary
import com.music.sonariq.util.DeviceInfo
import java.text.DateFormat
import java.util.Date

/**
 * The Sonariq Admin Control Center: this device's owner panel.
 *
 * Sonariq runs no account server, so this panel administers what actually
 * exists — this installation. Its numbers are this device's real listening
 * aggregates, caches, downloads and settings, measured where they are shown
 * ([ListeningStats], [StorageManager], [AdminStats]); anything a server
 * would be needed for (other people's accounts, cross-user analytics) is
 * shown as an honest unavailable state rather than invented. Ownership is
 * the one-time device claim in [AdminStore], and its limits are stated in
 * the Security section rather than dressed up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var owner by remember { mutableStateOf(AdminStore.owner(context)) }
    var section by remember { mutableStateOf<AdminSection?>(null) }

    BackHandler(enabled = section != null) { section = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoundlineGlyph()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                owner == null -> "Sonariq Admin setup"
                                section != null -> section!!.title
                                else -> "Sonariq Admin"
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (section != null) section = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        val currentOwner = owner
        if (currentOwner == null) {
            AdminClaimView(
                modifier = Modifier.fillMaxSize().padding(padding),
                onClaimed = { owner = it },
            )
            return@Scaffold
        }
        val currentSection = section
        if (currentSection == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    AdminOwnerHeader(currentOwner)
                }
                // Each of the twelve sections is its own glass panel row:
                // the section icon in an emerald-tinted circle, its title and
                // subtitle, and a chevron — tapping opens the same section
                // body as before.
                items(AdminSection.entries) { entry ->
                    val rowShape = RoundedCornerShape(18.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(GlassTokens.panelSurface, rowShape)
                            .glassEdge(rowShape)
                            .clickable { section = entry }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        } else {
            AdminSectionBody(
                section = currentSection,
                owner = currentOwner,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

enum class AdminSection(val title: String, val subtitle: String, val icon: ImageVector) {
    Overview("Overview", "Owner, version, listening totals, system status", Icons.Rounded.Dashboard),
    Users("Users", "Accounts on this device", Icons.Rounded.People),
    Music("Music catalog", "Most played tracks and artists on this device", Icons.Rounded.MusicNote),
    Autoplay("Smart Autoplay", "Engine settings and recommendation freshness", Icons.Rounded.AutoAwesome),
    Lyrics("Lyrics management", "Providers, cache and timing quality", Icons.Rounded.Subtitles),
    Analytics("Analytics", "Listening patterns recorded on this device", Icons.Rounded.BarChart),
    Health("System health", "Server, storage and cache status", Icons.Rounded.HealthAndSafety),
    Announcements("Announcements", "Owner notices kept on this device", Icons.Rounded.Campaign),
    Config("App configuration", "Feature switches that take effect immediately", Icons.Rounded.Settings),
    Security("Security center", "Ownership record and its limits", Icons.Rounded.Shield),
    Audit("Audit log", "Every administrative action, recorded", Icons.Rounded.History),
}

@Composable
private fun AdminOwnerHeader(owner: OwnerRecord) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).glassEdge(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = GlassTokens.panelSurface,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Eyebrow(text = "Admin")
            Spacer(Modifier.height(6.dp))
            Text("Sonariq Admin", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Owner: ${owner.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Ownership claimed ${formatAdminDateTime(owner.claimedAtMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${DateFormat.getDateTimeInstance().format(Date())} • Version " +
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one-time ownership claim. Shown only while no owner exists on this
 * device; the moment a claim lands, this is replaced by the panel and can
 * never be reached again.
 */
@Composable
private fun AdminClaimView(modifier: Modifier = Modifier, onClaimed: (OwnerRecord) -> Unit) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var alreadyClaimed by remember { mutableStateOf<OwnerRecord?>(null) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Eyebrow(text = "Admin")
        Text("Claim ownership of this device", style = MaterialTheme.typography.titleLarge)
        Text(
            "Sonariq has no account server, so the admin panel belongs to this " +
                "installation: the first setup here claims ownership permanently, " +
                "under a generated owner ID that never changes. No second claim " +
                "is possible afterwards, and ownership cannot be transferred or " +
                "removed from inside the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The panel shows this device's real data only — listening totals, " +
                "lyrics cache, downloads and settings. It cannot see or manage " +
                "other people's accounts, because Sonariq accounts do not exist " +
                "on a server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Your owner label (for example, your name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Claim ownership")
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Claim ownership permanently?") },
            text = {
                Text(
                    "This can only be done once on this device. The claim is " +
                        "recorded with a permanent owner ID and written to the " +
                        "audit log.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        when (val result = AdminStore.claimOwner(context, label)) {
                            is OwnerClaim.Result.Claimed -> onClaimed(result.record)
                            is OwnerClaim.Result.AlreadyClaimed -> alreadyClaimed = result.existing
                        }
                    },
                ) { Text("Claim") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
    alreadyClaimed?.let { existing ->
        AlertDialog(
            onDismissRequest = { alreadyClaimed = null },
            title = { Text("Ownership already claimed") },
            text = {
                Text(
                    "This device was claimed by ${existing.label} on " +
                        formatAdminDateTime(existing.claimedAtMs) +
                        ". A second claim is not possible.",
                )
            },
            confirmButton = {
                TextButton(onClick = { alreadyClaimed = null }) { Text("OK") }
            },
        )
    }
}

/** The Overview section: identity, real listening totals, live server probe. */
@Composable
internal fun AdminOverviewSection(owner: OwnerRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var usage by remember { mutableStateOf<StorageManager.Usage?>(null) }
    var server by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        summary = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
        usage = runCatching { StorageManager.measure(context) }.getOrNull()
        val probe = runCatching {
            ListenTogether.probeHealthWithLatency(ListenTogether.defaultServer, 10_000L)
        }.getOrNull()
        server = when {
            probe == null -> "Unknown"
            probe.isOnline -> "Online (${probe.latencyMs} ms)"
            else -> "Offline"
        }
    }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AdminInfoCard("Owner") {
                AdminStatRow("Owner label", owner.label)
                AdminStatRow("Owner ID", owner.id)
                AdminStatRow("Claimed", formatAdminDateTime(owner.claimedAtMs))
                AdminStatRow("Device", DeviceInfo.displayName)
                AdminStatRow("Android", DeviceInfo.androidVersion)
                AdminStatRow(
                    "App version",
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
            }
        }
        item {
            AdminInfoCard("Listening on this device (all time)") {
                val current = summary
                if (current == null) {
                    AdminNote("Measuring…")
                } else if (current.isEmpty) {
                    AdminNote("Nothing played yet. Totals appear here as this device listens.")
                } else {
                    AdminStatRow("Total plays", "${current.totalPlays}")
                    AdminStatRow("Listening time", formatAdminDuration(current.totalMs))
                    AdminStatRow("Different songs", "${current.distinctSongs}")
                    AdminStatRow("Different artists", "${current.distinctArtists}")
                    AdminStatRow("Different albums", "${current.distinctAlbums}")
                    current.busiestDay?.let {
                        AdminStatRow("Biggest day", "$it (${formatAdminDuration(current.busiestDayMs)})")
                    }
                    current.since?.let { AdminStatRow("Listening since", it) }
                }
            }
        }
        item {
            AdminInfoCard("System") {
                AdminStatRow("Listen Together server", server ?: "Checking…")
                val current = usage
                if (current == null) {
                    AdminNote("Measuring storage…")
                } else {
                    AdminStatRow("Sonariq storage", StorageManager.formatBytes(current.totalBytes))
                    AdminStatRow("Downloads", StorageManager.formatBytes(current.downloadsBytes))
                    AdminStatRow("Caches", StorageManager.formatBytes(current.clearableBytes))
                }
            }
        }
    }
}

@Composable
internal fun AdminInfoCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().glassEdge(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = GlassTokens.panelSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun AdminStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun AdminNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AdminLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

internal fun formatAdminDateTime(ms: Long): String =
    if (ms <= 0L) "Unknown" else DateFormat.getDateTimeInstance().format(Date(ms))

internal fun formatAdminDate(ms: Long): String =
    if (ms <= 0L) "Unknown" else DateFormat.getDateInstance().format(Date(ms))

internal fun formatAdminDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
