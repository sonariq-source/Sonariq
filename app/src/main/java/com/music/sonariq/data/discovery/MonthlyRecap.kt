package com.music.sonariq.data.discovery

import com.music.sonariq.data.stats.ArtistFacts
import com.music.sonariq.data.stats.ListeningStats
import com.music.sonariq.data.stats.StoredBucket
import com.music.sonariq.data.stats.TrackEntry
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The month in music, as one shareable story.
 *
 * ## Built from the stored buckets, not from a period summary
 *
 * [ListeningStats.summary] answers the fixed periods — this week, this month,
 * this year, all time. A recap is about *a* month, including past ones, so it
 * reads the month's own bucket file ([ListeningStats.exportAll]) and reduces
 * it directly. The ranking rules are the same ones the Replay uses (by
 * milliseconds, then plays), so the recap agrees with the Replay about the
 * same month.
 *
 * ## Everything is pure past [recapForMonth]
 *
 * [recapFromBucket] takes a bucket and returns the recap with no I/O, which
 * is what makes it unit-testable. [recapForMonth] is the thin suspend wrapper
 * that finds the bucket on disk.
 */
data class RecapTrack(
    val title: String,
    val artist: String,
    val art: String?,
    val ms: Long,
    val plays: Int,
)

data class RecapArtist(
    val name: String,
    val art: String?,
    val ms: Long,
    val plays: Int,
)

data class MonthlyRecap(
    val month: YearMonth,
    /** "October 2026", in the device locale. */
    val label: String,
    val totalMs: Long,
    val totalPlays: Int,
    val topTracks: List<RecapTrack>,
    val topArtists: List<RecapArtist>,
    val topGenre: String?,
    /** The month's longest listening streak, in days. */
    val streakDays: Int,
    /** Day-of-month with the most listening, or null. */
    val busiestDay: Int?,
    val distinctTracks: Int,
) {
    val minutes: Long get() = totalMs / 60_000
    val isEmpty: Boolean get() = topTracks.isEmpty()
}

/** Every month with listening on this device, newest first. */
suspend fun recapMonths(): List<YearMonth> =
    ListeningStats.months().sortedDescending()

/** The recap for [month], or null when nothing was recorded that month. */
suspend fun recapForMonth(month: YearMonth): MonthlyRecap? {
    val bucket = ListeningStats.exportAll()
        .firstOrNull { it.month == month.toString() }
        ?: return null
    return recapFromBucket(bucket)
}

/**
 * Reduces one stored bucket to its recap. Pure — no I/O — so it is
 * unit-testable without a device.
 */
fun recapFromBucket(bucket: StoredBucket): MonthlyRecap {
    val month = YearMonth.parse(bucket.month)

    val topTracks = bucket.tracks
        .sortedWith(compareByDescending<TrackEntry> { it.ms }.thenByDescending { it.plays })
        .take(TOP_TRACKS)
        .map {
            RecapTrack(
                title = it.title.ifBlank { it.id },
                artist = it.artist,
                art = it.art,
                ms = it.ms,
                plays = it.plays,
            )
        }
    val topArtists = bucket.artists
        .sortedWith(compareByDescending<com.music.sonariq.data.stats.NameEntry> { it.ms }.thenByDescending { it.plays })
        .take(TOP_ARTISTS)
        .map {
            RecapArtist(
                name = it.name,
                art = ArtistFacts.imageFor(it.name) ?: it.art,
                ms = it.ms,
                plays = it.plays,
            )
        }
    // Genres are derived from the artist tags, the same way the Replay does —
    // see ListeningStats' merge. An artist with no tag yet simply doesn't vote.
    val genreMs = LinkedHashMap<String, Long>()
    topArtists.forEach { artist ->
        ArtistFacts.genresFor(artist.name).forEach { genre ->
            genreMs[genre] = (genreMs[genre] ?: 0L) + artist.ms
        }
    }
    val topGenre = genreMs.maxByOrNull { it.value }?.key

    // The month's days as YYYY-MM-DD, so the shared streak walk can count them.
    val days = bucket.days.mapNotNull { (day, ms) ->
        if (day in 1..month.lengthOfMonth() && ms > 0) {
            month.atDay(day).toString() to ms
        } else {
            null
        }
    }.toMap()
    // The streak within the month: the walk counts back from the month's last
    // day, so a finished month's best run is reported whole rather than cut
    // off at whatever today happens to be.
    val streakDays = longestStreakIn(days, month)

    val busiestDay = bucket.days
        .filter { (day, ms) -> day in 1..month.lengthOfMonth() && ms > 0 }
        .maxByOrNull { it.value }?.key

    return MonthlyRecap(
        month = month,
        label = month.format(MONTH_LABEL),
        totalMs = bucket.tracks.sumOf { it.ms },
        totalPlays = bucket.tracks.sumOf { it.plays },
        topTracks = topTracks,
        topArtists = topArtists,
        topGenre = topGenre,
        streakDays = streakDays,
        busiestDay = busiestDay,
        distinctTracks = bucket.tracks.size,
    )
}

/**
 * The longest run of consecutive listening days inside [month].
 *
 * [days] maps `YYYY-MM-DD` to milliseconds. Pure, like [streakOf] — the
 * current-streak walk counts back from today, but a recap wants the month's
 * own longest run, so this walks every day of the month instead.
 */
fun longestStreakIn(days: Map<String, Long>, month: YearMonth): Int {
    var best = 0
    var run = 0
    var date = month.atDay(1)
    val end = month.atEndOfMonth()
    while (!date.isAfter(end)) {
        if ((days[date.toString()] ?: 0L) > 0L) {
            run++
            if (run > best) best = run
        } else {
            run = 0
        }
        date = date.plusDays(1)
    }
    return best
}

private val MONTH_LABEL =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private const val TOP_TRACKS = 5
private const val TOP_ARTISTS = 5
