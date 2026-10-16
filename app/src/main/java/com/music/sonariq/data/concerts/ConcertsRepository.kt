package com.music.sonariq.data.concerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.music.sonariq.MainActivity
import com.music.sonariq.R
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.Http
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.ReplayPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * One upcoming show, as Ticketmaster's Discovery API reports it. Every field
 * is the API's own data — nothing here is estimated or filled in.
 */
@Serializable
data class ConcertEvent(
    val id: String,
    val artistName: String,
    val name: String,
    val venue: String?,
    val city: String?,
    val country: String?,
    /** `yyyy-MM-dd` in the venue's local time. */
    val localDate: String?,
    /** `HH:mm` in the venue's local time, or null. */
    val localTime: String?,
    val ticketUrl: String?,
    val imageUrl: String?,
)

data class ConcertsState(
    val loading: Boolean = false,
    val events: List<ConcertEvent> = emptyList(),
    /** The last check failed (bad key, no network). The cached list, if any, is still shown. */
    val error: Boolean = false,
    val lastChecked: Long = 0L,
)

/**
 * Upcoming concerts by the listener's top artists.
 *
 * The artists come from [ListeningStats] (all-time Replay data); the shows
 * come from Ticketmaster's Discovery API, which is free (5,000 calls a day)
 * but requires a consumer key — and that key is the user's to bring, entered
 * in Settings. Without it there is nothing honest to show, and the UI says
 * so rather than inventing concerts.
 *
 * Checks run once a day through [ConcertsWorker] while the toggle is on, and
 * on demand from Settings / the Replay section. New shows since the last
 * check raise a notification, so the section is not the only way to hear
 * about them.
 */
object ConcertsRepository {

    private const val TAG = "Sonariq"
    private const val MAX_ARTISTS = 10
    private const val MAX_EVENTS = 40
    private const val PREFS = "sonariq_concerts"
    private const val KEY_SEEN_IDS = "seen_event_ids"
    private const val CHANNEL_ID = "concert_alerts"
    private const val NOTIFICATION_ID = 0xC0CE

    private lateinit var appContext: Context

    private val _state = MutableStateFlow(ConcertsState())
    val state: StateFlow<ConcertsState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        loadCache()
        applySchedule()
    }

    /**
     * Follows the toggle and the key: scheduled while both are present,
     * cancelled otherwise. Safe to call before [init] — it just waits.
     */
    fun applySchedule() {
        if (!this::appContext.isInitialized) return
        if (AppSettings.concertAlertsEnabled.value &&
            AppSettings.ticketmasterApiKey.value.isNotBlank()
        ) {
            ConcertsWorker.schedule(appContext)
        } else {
            ConcertsWorker.cancel(appContext)
        }
    }

    /**
     * Looks up upcoming shows for the top artists. Returns the number of
     * shows that had not been seen before (and were notified about).
     */
    suspend fun refresh(context: Context? = null): Int = withContext(Dispatchers.IO) {
        val ctx = context ?: if (this::appContext.isInitialized) appContext else return@withContext 0
        if (!AppSettings.concertAlertsEnabled.value) return@withContext 0
        val key = AppSettings.ticketmasterApiKey.value.trim()
        if (key.isEmpty()) return@withContext 0
        _state.value = _state.value.copy(loading = true, error = false)
        try {
            val today = LocalDate.now().toString()
            val artists = ListeningStats.summary(ReplayPeriod.ALL_TIME)
                .artists.take(MAX_ARTISTS).map { it.title }
            val events = artists.flatMapIndexed { index, artist ->
                // Ticketmaster allows 5 requests a second; a beat between
                // calls keeps a ten-artist sweep comfortably under it.
                if (index > 0) delay(300)
                runCatching { queryArtist(key, artist) }.getOrDefault(emptyList())
            }
                .distinctBy { it.id }
                .filter { it.localDate != null && it.localDate >= today }
                .sortedBy { it.localDate }
                .take(MAX_EVENTS)
            persistCache(ctx, events)
            val fresh = notifyNew(ctx, events)
            _state.value = ConcertsState(
                loading = false,
                events = events,
                lastChecked = System.currentTimeMillis(),
            )
            fresh
        } catch (e: Exception) {
            Log.w(TAG, "concerts refresh failed: ${e.message}")
            _state.value = _state.value.copy(loading = false, error = true)
            0
        }
    }

    private fun queryArtist(key: String, artist: String): List<ConcertEvent> {
        val url = "https://app.ticketmaster.com/discovery/v2/events.json".toHttpUrl()
            .newBuilder()
            .addQueryParameter("apikey", key)
            .addQueryParameter("keyword", artist)
            .addQueryParameter("classificationName", "music")
            .addQueryParameter("sort", "date,asc")
            .addQueryParameter("size", "20")
            .addQueryParameter(
                "startDateTime",
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // A 401 here almost always means the key is wrong or missing —
                // the section's error line says as much, honestly.
                Log.w(TAG, "ticketmaster search for '$artist': HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body.string()
            return json.decodeFromString<TmEventsResponse>(body)
                ._embedded?.events.orEmpty()
                .mapNotNull { it.toConcertEvent(artist) }
        }
    }

    /**
     * Announces shows that were not in the last check. The seen-ids are
     * recorded whether or not the notification goes out, so a denied
     * permission does not re-announce the same shows every day.
     */
    private fun notifyNew(ctx: Context, events: List<ConcertEvent>): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty()
        val fresh = events.filter { it.id !in seen }.take(5)
        prefs.edit()
            .putStringSet(KEY_SEEN_IDS, (seen + events.map { it.id }).toSet())
            .apply()
        if (fresh.isEmpty()) return 0
        if (!canPostNotifications(ctx)) return fresh.size
        val openApp = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(ctx.getString(R.string.concerts_notification_title))
            .setContentText(
                ctx.resources.getQuantityString(
                    R.plurals.concerts_notification_text,
                    fresh.size,
                    fresh.size,
                ),
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return fresh.size
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.concerts_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = ctx.getString(R.string.concerts_channel_description)
                },
            )
            manager.notify(NOTIFICATION_ID, notification)
        }
        return fresh.size
    }

    private fun canPostNotifications(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun cacheFile(ctx: Context): File = File(ctx.cacheDir, "concerts.json")

    private fun persistCache(ctx: Context, events: List<ConcertEvent>) {
        runCatching {
            cacheFile(ctx).writeText(json.encodeToString(events))
        }
    }

    private fun loadCache() {
        val events = runCatching {
            json.decodeFromString<List<ConcertEvent>>(cacheFile(appContext).readText())
        }.getOrDefault(emptyList())
        val today = LocalDate.now().toString()
        _state.value = ConcertsState(
            events = events.filter { it.localDate != null && it.localDate >= today },
            lastChecked = cacheFile(appContext).lastModified(),
        )
    }

    // ── Ticketmaster response shapes ────────────────────────────────────────

    @Serializable
    private data class TmEventsResponse(val _embedded: TmEmbedded? = null)

    @Serializable
    private data class TmEmbedded(val events: List<TmEvent> = emptyList())

    @Serializable
    private data class TmEvent(
        val id: String,
        val name: String,
        val url: String? = null,
        val images: List<TmImage> = emptyList(),
        val dates: TmDates? = null,
        val _embedded: TmEventEmbedded? = null,
    )

    @Serializable
    private data class TmImage(val url: String? = null)

    @Serializable
    private data class TmDates(val start: TmStart? = null)

    @Serializable
    private data class TmStart(
        val localDate: String? = null,
        val localTime: String? = null,
    )

    @Serializable
    private data class TmEventEmbedded(val venues: List<TmVenue> = emptyList())

    @Serializable
    private data class TmVenue(
        val name: String? = null,
        val city: TmNamed? = null,
        val country: TmNamed? = null,
    )

    @Serializable
    private data class TmNamed(val name: String? = null)

    private fun TmEvent.toConcertEvent(artist: String): ConcertEvent? {
        val venue = _embedded?.venues?.firstOrNull()
        return ConcertEvent(
            id = id,
            artistName = artist,
            name = name,
            venue = venue?.name,
            city = venue?.city?.name,
            country = venue?.country?.name,
            localDate = dates?.start?.localDate,
            localTime = dates?.start?.localTime,
            ticketUrl = url,
            imageUrl = images.firstOrNull()?.url,
        )
    }
}
