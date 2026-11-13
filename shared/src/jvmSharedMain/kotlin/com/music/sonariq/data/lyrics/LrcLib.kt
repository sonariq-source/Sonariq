package com.music.sonariq.data.lyrics

import com.music.sonariq.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs

/**
 * Lyrics from LRCLIB — a free, key-less, community lyrics database.
 *
 * Two calls: an exact `get` keyed on artist + title + duration, and a fuzzy
 * `search` when that misses (YouTube Music titles carry "(From ...)" and
 * "| Official Video" noise that the exact endpoint won't match). Results are
 * line-synced `[mm:ss.xx]` LRC — LRCLIB has no word-level timing, so
 * highlighting is per line.
 */
object LrcLib {

    private const val BASE = "https://lrclib.net/api"
    private const val AGENT = "Sonariq (https://github.com/sonariq-source/Sonariq)"

    private val json = Json { ignoreUnknownKeys = true }

    /** Synced lyrics for a track, or null when nothing usable is published. */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.clean()
            val cleanArtist = artist.clean()
            val seconds = (durationMs / 1000).toInt()

            val exact = runCatching {
                exactMatch(cleanTitle, cleanArtist, seconds, title, artist)
            }.getOrNull()
            val synced = exact ?: runCatching {
                bestSearchHit(cleanTitle, cleanArtist, seconds, title, artist)
            }.getOrNull()
            synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        }

    /**
     * The exact endpoint, keyed on artist + title + duration — and still
     * verified against what it hands back. The endpoint answers with the
     * record it matched, whose own name ships in the same payload: if
     * that name is not this track's (the cleaning in [clean] can reshape
     * a title past recognition, and a database can simply be wrong), the
     * answer is refused rather than believed. A refusal is a miss, and a
     * miss is recoverable; the wrong song's words are not.
     */
    private fun exactMatch(
        title: String,
        artist: String,
        seconds: Int,
        rawTitle: String,
        rawArtist: String,
    ): String? {
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("duration", seconds.toString())
            .build()
        val body = get(url.toString()) ?: return null
        val record = json.parseToJsonElement(body) as? JsonObject ?: return null
        if (!identityAccepted(record, rawTitle, rawArtist)) return null
        return record["syncedLyrics"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Fuzzy fallback. A search hit is only ever a *claim* about which
     * recording it is, so each hit's own title, artist and duration are
     * checked against the track before its lyrics are considered
     * ([identityAccepted] plus the duration window), and among the hits
     * that pass, whichever is closest in length to what we're actually
     * playing wins — same song, different edit, would drift otherwise.
     *
     * This check is the whole point of the fallback's shape: it used to
     * pick on duration alone, and a different song that happened to run
     * about as long as this one — another artist's, another title's —
     * was answered with in full. Duration is a property of thousands of
     * songs; a name is not.
     */
    private fun bestSearchHit(
        title: String,
        artist: String,
        seconds: Int,
        rawTitle: String,
        rawArtist: String,
    ): String? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val body = get(url.toString()) ?: return null
        val hits = json.parseToJsonElement(body) as? JsonArray ?: return null
        return hits.mapNotNull { it as? JsonObject }
            .filter { it["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            .filter { identityAccepted(it, rawTitle, rawArtist) }
            .filter {
                seconds <= 0 ||
                    abs((it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - seconds) <=
                    SEARCH_DURATION_TOLERANCE_SECONDS
            }
            .minByOrNull {
                val d = it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                abs(d - seconds)
            }
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Whether a LRCLIB record's own title and artist make it this
     * track's recording — see [RecordingMatch.identityAccepted]. A
     * record that does not name itself cannot be verified and is not
     * believed: the fields are part of both endpoints' payloads, so an
     * absent one is a malformed answer, not a gap to guess across.
     */
    private fun identityAccepted(
        record: JsonObject,
        rawTitle: String,
        rawArtist: String,
    ): Boolean {
        val hitTitle = record["trackName"]?.jsonPrimitive?.contentOrNull ?: return false
        val hitArtist = record["artistName"]?.jsonPrimitive?.contentOrNull ?: return false
        return RecordingMatch.identityAccepted(rawTitle, rawArtist, hitTitle, hitArtist)
    }

    /** How far a search hit's length may sit from the track's and still be the same recording. */
    private const val SEARCH_DURATION_TOLERANCE_SECONDS = 10.0

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /**
     * `[mm:ss.xx] words`. Metadata tags carry no timestamp and fall out on
     * their own. A stamp with no words marks an instrumental break; those are
     * kept, but only where the silence is long enough to be worth showing —
     * otherwise the line would blink out between two sung phrases. A stamp
     * with nothing after it closes the final line, so it always survives.
     */
    fun parseLrc(lrc: String): List<LyricLine> {
        val all = lrc.lineSequence().mapNotNull { line ->
            val match = STAMP.find(line) ?: return@mapNotNull null
            val (minutes, seconds, fraction) = match.destructured
            // Two digits mean centiseconds, three mean milliseconds.
            val fractionMs = when (fraction.length) {
                2 -> fraction.toLong() * 10
                3 -> fraction.toLong()
                else -> 0L
            }
            val body = line.substring(match.range.last + 1)
            // Detect the RTL marker that was written by [toEnhancedLrc].
            val (text, alignment) = stripAlignmentMarker(body)
            LyricLine(
                timeMs = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs,
                // Stripped rather than rebuilt from the runs below: the spacing
                // and punctuation between two words belong to the line, and
                // re-joining the words with single spaces would quietly rewrite
                // a line that never had them.
                text = text.replace(WORD_STAMP, "").trim(),
                words = parseWordRuns(text),
                alignment = alignment,
            )
        }.sortedBy { it.timeMs }.toList()

        val kept = all.filterIndexed { index, line ->
            if (!line.isGap) return@filterIndexed true
            // A trailing stamp closes off the last line — that's the outro.
            val next = all.getOrNull(index + 1) ?: return@filterIndexed true
            next.timeMs - line.timeMs >= MIN_GAP_MS
        }

        // Nothing stands for the intro — LRC files start at the first sung
        // word — so give the run-up its own break when it's long enough.
        val first = kept.firstOrNull() ?: return kept
        return if (!first.isGap && first.timeMs >= MIN_GAP_MS) {
            listOf(LyricLine(0L, "")) + kept
        } else {
            kept
        }
    }

    /**
     * Strip the alignment marker written by [toEnhancedLrc] and return the
     * clean body along with the recovered alignment.
     *
     * The marker is `<R>` — a word-stamp-looking token that never appears in
     * genuine A2 output (word stamps use `<mm:ss.xx>`, not bare `<R>`). It sits
     * at the very start of the body, before the first real stamp.
     */
    private fun stripAlignmentMarker(body: String): Pair<String, LyricAlignment> {
        if (body.startsWith(ALIGNMENT_MARKER)) {
            return body.substringAfter(ALIGNMENT_MARKER) to LyricAlignment.End
        }
        return body to LyricAlignment.Start
    }

    private const val ALIGNMENT_MARKER = "<R>"

    /**
     * YouTube Music titles are noisy — "(From "Raees")", "| Official Video",
     * "(Lyrical)" — and LRCLIB matches on the plain song name.
     */
    private fun String.clean(): String = this
        .replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    /**
     * The `<mm:ss.xx>` runs of an "enhanced" A2 line, as words.
     *
     * Each run ends where the next one starts, which is why a line written by
     * [toEnhancedLrc] closes with a bare stamp: that last one names no word, it
     * just states where the previous one stopped. A run with no text is
     * therefore a terminator rather than a word, here and in the files other
     * A2 writers produce.
     *
     * Empty for a plain line, which is what keeps [LyricLine.isWordSynced]
     * honest — a line-synced source stays line-synced through this.
     */
    private fun parseWordRuns(body: String): List<LyricWord> {
        val marks = WORD_STAMP.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val runs = marks.mapIndexed { index, mark ->
            val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
            msOf(mark) to body.substring(mark.range.last + 1, until)
        }
        // The next run's stamp is this one's end — including when that run is
        // the closing terminator, which is the only thing that gives the last
        // word of a line an end at all. A run with no space after it is one
        // syllable of a word that carries on, which is how [toEnhancedLrc]
        // writes a word whose syllables were timed apart.
        return wordsFromRuns(
            runs.mapIndexed { index, (startMs, text) ->
                TimedRun(startMs, runs.getOrNull(index + 1)?.first ?: startMs, text)
            },
        )
    }

    private fun msOf(mark: MatchResult): Long {
        val (minutes, seconds, fraction) = mark.destructured
        val fractionMs = when (fraction.length) {
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    private val STAMP = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{2,3})]""")
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
    // What names a *different recording* is deliberately absent from
    // this pattern: "(Remix)", "(Live)", "(Acoustic)" stay on the title
    // asked for, exactly as [String.forLyricsSearch] leaves them —
    // stripping one turns a search for one recording into a search for
    // another, and the hit validation in [identityAccepted] reads the
    // markers from the uncleaned title for the same reason.
    private val NOISE = Regex(
        """\((?:from|feat\.?|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
