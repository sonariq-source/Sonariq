package com.music.sonariq.data.lyrics

/**
 * Recording identity, judged from names: whether a candidate a provider
 * found can be the recording the player is holding, before its words are
 * believed.
 *
 * Everything else in the lookup matches a *name* against a catalogue, and
 * a name is where wrong lyrics get in. Two recordings share a title
 * routinely — the original and its remix, the studio cut and the live
 * one, one artist's song and another's cover of a different song with the
 * same name — and a search that only asks "is it called roughly this, and
 * about this long" will sooner or later answer with the wrong one's
 * words, in full, in sync with nothing. The checks here are the cheapest
 * line of defence: the candidate's own title and artist, which the
 * providers that search (rather than answer by ISRC or by video id)
 * already have in hand, compared against the track's.
 *
 * Pure and deterministic, like [LyricsLookupPolicy]: no I/O, no clock —
 * the same names always judge the same way, so the providers' acceptance
 * decisions are testable without a network.
 */
object RecordingMatch {

    /** A way one recording of a song differs from another, named in its title. */
    enum class Version {
        REMIX,
        LIVE,
        ACOUSTIC,
        SPED_UP,
        SLOWED,
        RADIO_EDIT,

        /**
         * A remaster of the same performance. Judged soft — see
         * [versionsCompatible] — because catalogues file remasters under
         * the plain title as often as not, and the words and their timing
         * are the studio performance's either way.
         */
        REMASTERED,
    }

    /**
     * The versions whose presence changes what is sung or how it is
     * timed, so a mismatch in either direction is a different recording:
     * a remix re-orders and repeats, a live cut stretches and talks, an
     * acoustic take re-plays it, a sped-up or slowed one rescales every
     * timestamp, a radio edit cuts verses. [Version.REMASTERED] is
     * deliberately absent — see its doc.
     */
    private val HARD_VERSIONS = setOf(
        Version.REMIX,
        Version.LIVE,
        Version.ACOUSTIC,
        Version.SPED_UP,
        Version.SLOWED,
        Version.RADIO_EDIT,
    )

    /**
     * The [Version]s [title] declares, read only from its packaging: the
     * contents of bracketed segments and the tail after a " - " dash.
     *
     * Only packaging is read because the words of a title are not
     * packaging — a song actually called "Live Forever" declares nothing,
     * while "Song (Live)" declares a recording. Brackets and dash tails
     * are where uploads and catalogues alike put version information, and
     * they are the same places [String.forLyricsSearch] preserves for
     * exactly this reason.
     */
    fun versionMarkers(title: String): Set<Version> {
        val segments = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (char in title) {
            when {
                char == '(' || char == '[' -> {
                    if (depth == 0) current.setLength(0)
                    depth++
                }
                char == ')' || char == ']' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0) segments += current.toString()
                    }
                }
                depth > 0 -> current.append(char)
            }
        }
        title.substringAfterLast(" - ", "").takeIf { it.isNotBlank() }
            ?.let { segments += it }
        val markers = segments.flatMap { markersIn(it) }.toMutableSet()
        // The same declarations made without packaging: a catalogue name
        // that simply ends in the word ("Song Remix", "Song Live",
        // "Song Acoustic") declares its version with its last word, and
        // missing it would judge the unbracketed form of a remix a plain
        // title. Only the final word is read — anywhere else in the name
        // the word is part of the song's actual title.
        when (normalizeName(title).substringAfterLast(' ')) {
            "remix" -> markers += Version.REMIX
            "live" -> markers += Version.LIVE
            "acoustic" -> markers += Version.ACOUSTIC
        }
        return markers
    }

    /** The versions one packaging segment names, if any. */
    private fun markersIn(segment: String): List<Version> {
        val text = segment.lowercase().replace('-', ' ')
        val found = ArrayList<Version>()
        if ("remix" in text) found += Version.REMIX
        if ("sped up" in text || "spedup" in text) found += Version.SPED_UP
        if ("slowed" in text || "slow version" in text) found += Version.SLOWED
        if ("acoustic" in text) found += Version.ACOUSTIC
        if ("radio edit" in text) found += Version.RADIO_EDIT
        if ("remaster" in text) found += Version.REMASTERED
        // Word-boundaried, so "olive" and "delivered" do not declare a
        // concert: the segment's words are compared whole.
        if (text.split(WORD_SPLIT).any { it == "live" }) found += Version.LIVE
        return found
    }

    /**
     * Whether a candidate titled [candidateTitle] can be the recording
     * titled [trackTitle]: their hard version markers must agree exactly.
     * A remix asked for and a plain title offered disagree (the words are
     * the original's), and so do a plain title asked for and a live one
     * offered. [Version.REMASTERED] differences are tolerated — see
     * [HARD_VERSIONS].
     */
    fun versionsCompatible(trackTitle: String, candidateTitle: String): Boolean {
        val track = versionMarkers(trackTitle) intersect HARD_VERSIONS
        val candidate = versionMarkers(candidateTitle) intersect HARD_VERSIONS
        return track == candidate
    }

    /**
     * A name in comparable form: lowercased, punctuation and symbols
     * folded to spaces, whitespace collapsed. Bracketed packaging is kept
     * as words — it is removed from consideration by the version checks,
     * not by throwing text away here, where a containment comparison
     * still needs it.
     */
    fun normalizeName(raw: String): String {
        val out = StringBuilder(raw.length)
        for (char in raw.lowercase()) {
            out.append(if (char.isLetterOrDigit()) char else ' ')
        }
        return out.toString().split(WORD_SPLIT).filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /**
     * Whether [candidate] can be the song named [query]: equal once
     * normalized, or one containing the other — a catalogue name carrying
     * a suffix the query dropped ("Song Part 2" filed against "Song"), or
     * the query carrying packaging the catalogue folds into the name.
     * Containment is only trusted between names long enough for it to
     * mean something; a two-letter title is contained in everything.
     */
    fun namesMatch(query: String, candidate: String): Boolean {
        val wanted = normalizeName(query)
        val offered = normalizeName(candidate)
        if (wanted.isEmpty() || offered.isEmpty()) return false
        if (wanted == offered) return true
        val shorter = minOf(wanted.length, offered.length)
        if (shorter < MIN_CONTAINMENT_LENGTH) return false
        return wanted in offered || offered in wanted
    }

    /**
     * Whether [candidate] can be the artist named [query]: equal or
     * containing once normalized, or agreeing on the primary artist — the
     * first name before a separator — which is how a catalogue files a
     * collaboration the player credits in full, and vice versa.
     */
    fun artistMatches(query: String, candidate: String): Boolean {
        val wanted = normalizeName(query)
        val offered = normalizeName(candidate)
        if (wanted.isEmpty() || offered.isEmpty()) return false
        if (wanted == offered || wanted in offered || offered in wanted) return true
        val wantedPrimary = primaryArtist(wanted)
        val offeredPrimary = primaryArtist(offered)
        return wantedPrimary.isNotEmpty() && wantedPrimary == offeredPrimary
    }

    /** The first artist of an already-normalized credit line. */
    private fun primaryArtist(normalized: String): String =
        normalized.split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()

    /**
     * The whole judgement in one call, for providers holding a candidate's
     * title and artist: the names must match, the artists must match, and
     * the recording versions must be compatible. Only then is the
     * candidate the track's own recording, as far as names can tell.
     */
    fun identityAccepted(
        trackTitle: String,
        trackArtist: String,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean =
        namesMatch(trackTitle, candidateTitle) &&
            artistMatches(trackArtist, candidateArtist) &&
            versionsCompatible(trackTitle, candidateTitle)

    /** Below this length, one name containing another is coincidence, not a match. */
    private const val MIN_CONTAINMENT_LENGTH = 4

    private val WORD_SPLIT = Regex("""\s+""")

    private val ARTIST_SEPARATORS = Regex("""\b(?:feat|ft|featuring|with|and)\b|,|&| x """)
}
