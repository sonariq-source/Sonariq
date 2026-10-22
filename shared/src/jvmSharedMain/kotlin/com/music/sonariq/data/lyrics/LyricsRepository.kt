package com.music.sonariq.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, and the catalogue
 *    [BiniLyrics] itself answers from.
 *  - [BetterLyrics], [PaxSenix] and [BiniLyrics] — Apple Music TTML,
 *    per-syllable, from three independent hosts so one having a bad day
 *    doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  - [Unison] — contributed rather than licensed; thin, and occasionally the
 *    only place a track exists.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried, and a track with no
 * lyrics anywhere would spend the best part of a minute finding that out with
 * eight of them. Run together, a miss costs whatever the slowest one needed
 * to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 *
 * ### Matching on the recording
 *
 * Everything above matches a *name*, and a name is ambiguous in a way that
 * matters: a single and its album cut share a title, an artist and very nearly
 * a length, and routinely differ in the words. An ISRC names one recording and
 * settles it. Two sources accept one — [BiniLyrics] and [LyricsPlus] — and
 * [BiniLyrics] is also the one that hands them out, reporting the ISRC of
 * whatever its own search matched.
 *
 * So the recording is settled *before* anybody is asked for words: one small
 * search against [BiniLyrics], whose answer is a name every other source can
 * use. It costs a round trip at the head of the lookup, which is why it is
 * capped at [IDENTIFY_TIMEOUT_MS] and why its result is kept against the video
 * id — a track asked about twice pays for this once. A downloaded or local file
 * can skip it entirely: its own tags name the recording, which is what the
 * [isrc] parameter is for.
 *
 * Only run when the user has [LyricsSource.BINI_LYRICS] enabled. It is a
 * request to a third party like any other, and a source somebody has turned off
 * is a source this app does not contact — not even for something it would only
 * use to help the sources they left on.
 *
 * Winners are kept on disk keyed by video id (ISRC as the fallback), gzipped
 * beside the translation cache under [CACHE_DIRECTORY], so a track asked about
 * twice pays the providers once. A cache hit reads back byte-for-byte what the
 * providers returned, including which [LyricTiming] bucket it fell into — it
 * never invents lyrics. Where [cacheDir] is null, everything stays in memory.
 */
object LyricsRepository {

    /** Lyrics, which source they turned out to come from, and how precisely they are timed. */
    data class Result(
        val source: LyricsSource,
        val lines: List<LyricLine>,
        val timing: LyricTiming = lines.lyricTiming(),
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        /**
         * Player lookups only: how long to wait for the whole race before
         * surfacing the best already-matched result through
         * [onDeadlineResult]. Null (the default, used by every background
         * prefetch) keeps the original run-to-completion behaviour.
         */
        deadlineMs: Long? = null,
        /**
         * Called at most once, when [deadlineMs] passes with a matched
         * result already in hand. The lookup keeps racing afterwards: the
         * returned value is the final winner, which may be a better
         * (word-synced) upgrade over the deadline result. Only results that
         * passed the race's own matching are ever reported here.
         */
        onDeadlineResult: ((Result) -> Unit)? = null,
    ): Result? {
        // A remembered genuine miss answers at once — no race, no wait —
        // until its TTL runs out. Checked before any shared work is joined
        // or started, so a missed track costs nothing to reopen.
        if (isKnownMiss(videoId, isrc)) return null
        // One race per track: a prefetch already running for this track is
        // the player's lookup too, rather than a second set of requests
        // racing the first. Callers' source callbacks are fanned out, so a
        // joining caller still hears every provider attempt.
        val key = inFlightKey(videoId, sources, order, prioritizeSyllableSync, isrc)
        val callbacks = SourceCallbacks(onSourceStarted, onSourceResult, onSourceCancelled)
        val entry = inFlight.computeIfAbsent(key) { createdKey ->
            val created = InFlightLookup()
            created.listeners += callbacks
            if (onDeadlineResult != null) created.deadlineListeners += onDeadlineResult
            created.deferred = lookupScope.async {
                try {
                    raceLyrics(
                        videoId = videoId,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        album = album,
                        sources = sources,
                        order = order,
                        prioritizeSyllableSync = prioritizeSyllableSync,
                        isrc = isrc,
                        emitStarted = created::emitStarted,
                        emitResult = created::emitResult,
                        emitCancelled = created::emitCancelled,
                        deadlineAtNanos = created::deadlineAtNanos,
                        emitDeadline = created::emitDeadline,
                    )
                } finally {
                    inFlight.remove(createdKey, created)
                }
            }
            created
        }
        if (callbacks !in entry.listeners) entry.listeners += callbacks
        // The deadline belongs to the shared lookup, not to whoever started
        // it: a player joining a background race still gets its deadline,
        // and the earliest deadline any caller asked for is the one used.
        if (deadlineMs != null) {
            val current = entry.requestedDeadlineMs
            if (current == null || deadlineMs < current) {
                entry.requestedDeadlineMs = deadlineMs
            }
        }
        if (onDeadlineResult != null) {
            // Joined after the deadline already fired: hand over what it
            // surfaced rather than leaving this caller waiting in silence.
            entry.addDeadlineListener(onDeadlineResult)?.let { onDeadlineResult(it) }
        }
        return entry.deferred.await()
    }

    /**
     * The provider race itself, run once per track in [lookupScope] and
     * shared by every concurrent caller — see [lyrics]. Previously this was
     * the whole of [lyrics]; the matching, ordering and winner rules are
     * unchanged.
     */
    private suspend fun raceLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
        emitStarted: (LyricsSource) -> Unit,
        emitResult: (LyricsSource, Result?) -> Unit,
        emitCancelled: (LyricsSource) -> Unit,
        /** The shared lookup's deadline as an absolute nanoTime, or null. */
        deadlineAtNanos: () -> Long?,
        emitDeadline: (Result) -> Unit,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        // Settled before anyone is asked for words, so every source that can
        // name the recording does. What the caller knows beats what we worked
        // out last time, and both beat asking again.
        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        // Already asked about this one: the disk cache answers before any
        // provider is even started, so a repeat lookup costs no round trips.
        readCache(videoId, known)?.let { return@coroutineScope it }
        val hit = if (known == null) {
            identify(videoId, searchTitle, searchArtist, durationMs, album, sequence)
        } else {
            null
        }
        val recording = known ?: hit?.isrc?.takeIf { it.isNotBlank() }
        val documents = SharedDocuments(this)

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) kotlinx.coroutines.CoroutineStart.LAZY else kotlinx.coroutines.CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = startMode) {
                emitStarted(source)
                try {
                    val found = fetch(
                        source,
                        videoId,
                        searchTitle,
                        searchArtist,
                        durationMs,
                        album,
                        recording,
                        hit,
                        documents::get,
                    )?.let { result(source, it) }
                    emitResult(source, found)
                    found
                } catch (cancelled: CancellationException) {
                    emitCancelled(source)
                    throw cancelled
                } catch (_: Exception) {
                    emitResult(source, null)
                    null
                }
            }
        }

        try {
            var lineSynced: Result? = null
            // Writes a winner through to the disk cache before handing it up,
            // so the next lookup for this track skips the providers entirely.
            // the owner: no interpolated word timings — if the winner has real
            // word sync it stays word-synced; if it's line-synced it stays
            // line-synced. Fake word timings are worse than honest lines.
            suspend fun deliver(result: Result): Result {
                val lines = result.lines
                val timed = result.copy(lines = lines, timing = lines.lyricTiming())
                writeCache(videoId, recording, timed)
                return timed
            }
            // The player's deadline, counted from the shared lookup's
            // start and read through [deadlineAtNanos] on every pass: a
            // caller can join an already-running background race and bring
            // its deadline with it. When the deadline passes, the best
            // result that has *already* matched is surfaced through
            // [emitDeadline] instead of the panel waiting on the slowest
            // straggler; the race then keeps going, and only a better-timed
            // (word-synced) result can replace what was surfaced — the
            // upgrade path below. Pure background lookups never have a
            // deadline requested, so for them the waits below are the
            // original unbounded ones, in short slices so a deadline
            // requested mid-wait is still honoured.
            var deadlineResult: Result? = null
            var deadlineFired = false
            // Only ever peeks at jobs that have already finished — awaiting
            // one here would start the lazy plain-text source early and
            // would wait on exactly the stragglers the deadline exists to
            // skip. What it picks goes through the race's own winner rules
            // ([LyricsLookupPolicy.pickDeadlineWinner]), so the deadline can
            // only surface a result the full race would itself have taken.
            suspend fun fireDeadline() {
                if (deadlineFired) return
                deadlineFired = true
                val completed = racing.map { (_, job) ->
                    if (job.isCompleted && job.getCompletionExceptionOrNull() == null) {
                        runCatching { job.await() }.getOrNull()
                    } else {
                        null
                    }
                }
                val best = LyricsLookupPolicy.pickDeadlineWinner(
                    completedInOrder = completed,
                    prioritizeSyllableSync = prioritizeSyllableSync,
                    fallback = lineSynced,
                ) ?: return
                val delivered = deliver(best)
                deadlineResult = delivered
                emitDeadline(delivered)
                // A word-synced deadline result is the best this race can
                // ever produce; the caller's return value is already decided.
                if (delivered.lines.any { it.isWordSynced }) {
                    throw DeadlineSettled(delivered)
                }
            }
            try {
                for ((source, job) in racing) {
                    // If we already found a line-synced or better result, skip Genius completely
                    if ((lineSynced != null || deadlineResult != null) &&
                        source == LyricsSource.GENIUS
                    ) {
                        continue
                    }

                    // Awaits [job], honouring the shared lookup's deadline
                    // whenever one has been requested by any caller.
                    suspend fun awaitJob(): Result? {
                        while (true) {
                            if (deadlineFired) {
                                // Past the deadline: keep collecting, but
                                // only a word-synced upgrade is acted on.
                                return runCatching { job.await() }.getOrNull()
                            }
                            val deadlineAt = deadlineAtNanos()
                            if (deadlineAt == null) {
                                withTimeoutOrNull(DEADLINE_POLL_MS) {
                                    runCatching { job.await() }.getOrNull()
                                }
                                if (job.isCompleted) {
                                    return runCatching { job.await() }.getOrNull()
                                }
                                // Still running and still no deadline: wait
                                // another slice.
                                continue
                            }
                            val remainingMs = (deadlineAt - System.nanoTime()) / NANOS_PER_MS
                            if (remainingMs <= 0L) {
                                fireDeadline()
                                return runCatching { job.await() }.getOrNull()
                            }
                            val raced = withTimeoutOrNull(remainingMs) {
                                runCatching { job.await() }.getOrNull()
                            }
                            if (raced == null && !job.isCompleted) {
                                // The wait timed out on a job still running:
                                // that is the deadline, not a miss.
                                fireDeadline()
                                return runCatching { job.await() }.getOrNull()
                            }
                            return raced
                        }
                    }
                    val found = awaitJob()
                    if (found == null) continue
                    if (found.lines.any { it.isWordSynced }) {
                        return@coroutineScope deliver(found)
                    }
                    if (deadlineResult != null) {
                        // Something is already on screen; a merely
                        // line-synced latecomer is not an upgrade.
                        if (lineSynced == null) lineSynced = found
                        continue
                    }
                    if (!prioritizeSyllableSync && found.lines.any { it.timeMs > 0 }) {
                        return@coroutineScope deliver(found)
                    }
                    if (lineSynced == null) lineSynced = found
                }
            } catch (settled: DeadlineSettled) {
                return@coroutineScope settled.result
            }
            lineSynced?.let { return@coroutineScope deliver(it) }
            deadlineResult?.let { return@coroutineScope it }
            // Every provider was asked and none matched: a genuine miss,
            // remembered so reopening this track answers promptly instead
            // of re-running the whole race. An empty source set is a
            // settings state, not a miss, and is never remembered.
            if (sequence.isNotEmpty()) recordMiss(videoId, recording)
            null
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
            documents.cancel()
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        /** What [identify] already found, where it ran; saves a second search. */
        hit: BiniLyrics.Hit?,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        val found = when (source) {
            LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc, get)
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
            LyricsSource.BINI_LYRICS ->
                (hit?.let { BiniLyrics.lyricsFor(it, get) }
                    ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc, get))
                    ?.also { remember(videoId, it.isrc) }
                    ?.lines
            LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
            LyricsSource.GENIUS -> Genius.lyrics(title, artist)
        }
        return found
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>): Result {
        val prepared = lines.withBackgroundVocals()
        return Result(source, prepared, prepared.lyricTiming())
    }

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Short on purpose. Knowing the recording makes every match better, but not
     * knowing it only puts things back where they were a release ago, and a
     * lyrics panel sitting empty because one host is having a slow morning is a
     * worse failure than a fuzzy match.
     */
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    /**
     * The one request made before the race: which recording is this?
     *
     * Skipped entirely when the source that answers it is switched off, and
     * given up on rather than waited out — see [IDENTIFY_TIMEOUT_MS]. What it
     * finds is remembered, so a track pays for this once rather than once per
     * lookup, and the hit is handed back so [BiniLyrics] need not search twice.
     */
    private suspend fun identify(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
    ): BiniLyrics.Hit? {
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            runCatching { BiniLyrics.identify(title, artist, durationMs, album) }.getOrNull()
        }
        if (hit == null) return null
        remember(videoId, hit.isrc)
        return hit
    }

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    // ---- Shared in-flight lookups -------------------------------------

    private const val NANOS_PER_MS = 1_000_000L

    /** Slice for waits that have no deadline yet; see the race loop. */
    private const val DEADLINE_POLL_MS = 250L

    /** Control flow for a deadline that surfaced a word-synced winner. */
    private class DeadlineSettled(val result: Result) : Exception()

    /** One caller's provider-attempt callbacks, fanned out from a shared race. */
    private data class SourceCallbacks(
        val started: ((LyricsSource) -> Unit)?,
        val result: ((LyricsSource, Result?) -> Unit)?,
        val cancelled: ((LyricsSource) -> Unit)?,
    )

    /**
     * A race in flight, shared by every concurrent caller for the same
     * track. Runs in [lookupScope] rather than any caller's scope, so one
     * caller leaving (a track skip, a page closing) never cancels work
     * another caller is still waiting on — and the finisher writes the
     * disk cache either way, which is what the next lookup reads.
     */
    private class InFlightLookup {
        lateinit var deferred: Deferred<Result?>
        val listeners = Collections.synchronizedList(mutableListOf<SourceCallbacks>())
        val deadlineListeners =
            Collections.synchronizedList(mutableListOf<(Result) -> Unit>())

        /** When this lookup started, for deadline arithmetic. */
        val startedAtNanos: Long = System.nanoTime()

        /** The earliest deadline any caller has asked for, if any. */
        @Volatile
        var requestedDeadlineMs: Long? = null

        fun deadlineAtNanos(): Long? =
            requestedDeadlineMs?.takeIf { it > 0 }
                ?.let { startedAtNanos + it * NANOS_PER_MS }

        @Volatile
        var deadlineResult: Result? = null
            private set

        fun emitStarted(source: LyricsSource) =
            listeners.toList().forEach { it.started?.invoke(source) }

        fun emitResult(source: LyricsSource, result: Result?) =
            listeners.toList().forEach { it.result?.invoke(source, result) }

        fun emitCancelled(source: LyricsSource) =
            listeners.toList().forEach { it.cancelled?.invoke(source) }

        fun emitDeadline(result: Result) {
            val targets = synchronized(this) {
                deadlineResult = result
                deadlineListeners.toList()
            }
            targets.forEach { it(result) }
        }

        /**
         * Registers a deadline listener, handing back the deadline result
         * when it already fired — atomically, so a listener can never be
         * both registered for the firing and handed the result separately.
         */
        fun addDeadlineListener(listener: (Result) -> Unit): Result? =
            synchronized(this) {
                if (listener !in deadlineListeners) deadlineListeners += listener
                deadlineResult
            }
    }

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, InFlightLookup>()

    /**
     * Identity of one shared lookup: the track, plus the settings that
     * decide its answer. Two callers asking with different source sets are
     * asking different questions and never share a race.
     */
    private fun inFlightKey(
        videoId: String,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
    ): String = buildString {
        append(videoId)
        append('|')
        append(sources.map { it.name }.sorted().joinToString(","))
        append('|')
        append(order.joinToString(",") { it.name })
        append('|')
        append(if (prioritizeSyllableSync) '1' else '0')
        append('|')
        append(isrc.orEmpty())
    }

    // ---- Negative cache ------------------------------------------------

    /** When each track last missed everywhere, this session. Keyed like [cacheKey]. */
    private val sessionMisses = ConcurrentHashMap<String, Long>()

    /**
     * Whether this track is a remembered genuine miss — every enabled
     * provider was asked, none matched, and the miss is still within
     * [LyricsLookupPolicy.NEGATIVE_TTL_MS]. In memory for the session and
     * on disk beside the lyrics cache, so a miss survives a restart too.
     * Never throws; an unreadable entry is simply not a miss.
     */
    suspend fun isKnownMiss(videoId: String, isrc: String? = null): Boolean {
        val key = cacheKey(videoId, isrc) ?: return false
        val now = System.currentTimeMillis()
        sessionMisses[key]?.let { missedAt ->
            if (LyricsLookupPolicy.isNegativeFresh(missedAt, now)) return true
            sessionMisses.remove(key, missedAt)
        }
        return withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock false
                val file = File(File(root, CACHE_DIRECTORY), "$key.miss")
                if (!file.isFile) return@withLock false
                val missedAt = runCatching { file.readText().trim().toLong() }.getOrNull()
                if (missedAt == null ||
                    !LyricsLookupPolicy.isNegativeFresh(missedAt, System.currentTimeMillis())
                ) {
                    file.delete()
                    false
                } else {
                    sessionMisses[key] = missedAt
                    true
                }
            }
        }
    }

    /** Remembers a genuine miss, in memory and (where there is one) on disk. */
    private suspend fun recordMiss(videoId: String, isrc: String?) {
        val key = cacheKey(videoId, isrc) ?: return
        val now = System.currentTimeMillis()
        sessionMisses[key] = now
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val directory = File(root, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                runCatching {
                    File(directory, "$key.miss").writeText(now.toString())
                }
            }
        }
    }

    /** Forgets a remembered miss — a later lookup found lyrics after all. */
    private fun forgetMiss(videoId: String, isrc: String?) {
        val key = cacheKey(videoId, isrc) ?: return
        sessionMisses.remove(key)
        val root = cacheDir ?: return
        runCatching { File(File(root, CACHE_DIRECTORY), "$key.miss").delete() }
    }

    /**
     * Where fetched lyrics are kept between runs — the phone's cache directory,
     * the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()

    /** Bump this and every file on disk quietly stops matching. */
    private const val CACHE_VERSION = 1
    private const val CACHE_DIRECTORY = "lyrics_v1"
    private const val MAX_CACHE_FILES = 2000

    @Serializable
    private data class CachedLyrics(
        val version: Int = CACHE_VERSION,
        val source: String,
        val timing: String,
        val lines: List<LyricLine>,
    )

    /**
     * Which cache file a lookup belongs to: the video id first, the ISRC
     * behind it as the fallback. The video id names the exact upload; an ISRC
     * names the recording, which is the same song on every upload. Pure, so
     * it is trivially testable.
     */
    fun cacheKey(videoId: String, isrc: String?): String? {
        val id = videoId.takeIf { it.isNotBlank() }
            ?: isrc?.takeIf { it.isNotBlank() } ?: return null
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "lyrics:$hex"
    }

    /**
     * Whatever a previous lookup cached for this track, or null.
     *
     * A hit skips the providers entirely — the lines are byte-for-byte what
     * they returned, nothing invented — and is read only after [cacheKey]
     * matches, so a wrong song never reads another's lyrics. Never throws.
     */
    suspend fun readCache(videoId: String, isrc: String?): Result? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock null
                val key = cacheKey(videoId, isrc) ?: return@withLock null
                val file = File(File(root, CACHE_DIRECTORY), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val cached = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedLyrics>(it.readText())
                    }
                    // Old format, or a source the enum no longer knows.
                    if (cached.version != CACHE_VERSION) return@runCatching null
                    file.setLastModified(System.currentTimeMillis())
                    Result(
                        source = LyricsSource.valueOf(cached.source),
                        lines = cached.lines,
                        timing = LyricTiming.valueOf(cached.timing),
                    )
                }.getOrNull()
            }
        }

    private suspend fun writeCache(videoId: String, isrc: String?, result: Result) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val key = cacheKey(videoId, isrc) ?: return@withLock
                val directory = File(root, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json.gz")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    val payload = CachedLyrics(
                        source = result.source.name,
                        timing = result.timing.name,
                        lines = result.lines,
                    )
                    GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                        it.write(json.encodeToString(payload))
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    // A hit retires any remembered miss for this track.
                    forgetMiss(videoId, isrc)
                    trimCache(directory)
                }.onFailure { temporary.delete() }
            }
        }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" || file.extension == "miss" }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        if (files.size > MAX_CACHE_FILES) files.take(files.size - MAX_CACHE_FILES).forEach(File::delete)
    }

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}
