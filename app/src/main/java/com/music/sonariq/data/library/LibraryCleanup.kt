package com.music.sonariq.data.library

import com.music.sonariq.data.model.Song

/**
 * Finds the mess in a library and reports it. Two jobs:
 *
 *  - **Duplicates** — same recording twice (or five times), found by real
 *    comparison: normalised title + artist, and duration within a few
 *    seconds. The same song from two uploads with different lengths is not
 *    the same recording and is not flagged.
 *  - **Missing metadata** — blank titles, artists, or album names, with a
 *    suggested fix derived from the file name (`Artist - Title.ext`) where
 *    the file name actually carries it.
 *
 * This reports; it never writes. Applying a fix means rewriting a playlist
 * entry or a tagged file, which belongs to an explicit user action in the UI
 * with the before/after on screen — never a silent sweep. The data classes
 * below are exactly what that UI needs to show.
 */
object LibraryCleanup {

    /**
     * One set of songs that are the same recording.
     *
     * @param reason how they matched, e.g. "Same title, artist and duration".
     * @param keep the entry to keep if the user dedupes: the downloaded or
     *   on-device copy first (it survives offline), then the most complete
     *   metadata, so the suggestion is never "delete the good one".
     */
    data class DuplicateGroup(
        val songs: List<Song>,
        val reason: String,
        val keep: Song,
    )

    /** A blank field, plus what the file name says it should be — or null when it says nothing. */
    data class MetadataGap(
        val song: Song,
        /** One of `"title"`, `"artist"`, `"album"`. */
        val field: String,
        val suggestion: String?,
    )

    /**
     * Group [songs] by normalised title+artist, then split each group by
     * duration: tracks within [DURATION_TOLERANCE_SECONDS] of each other are
     * the same recording. Tracks with no duration on either side still group
     * together — two entries that agree on title and artist and state no
     * length are duplicates by every measure available.
     */
    fun findDuplicates(songs: List<Song>): List<DuplicateGroup> {
        val byKey = songs.groupBy { normaliseKey(it.title, it.artist) }
        val groups = mutableListOf<DuplicateGroup>()
        for ((_, sameName) in byKey) {
            if (sameName.size < 2) continue
            if (sameName.all { it.title.isBlank() }) continue
            val byDuration = mutableListOf<MutableList<Song>>()
            for (song in sameName) {
                val seconds = parseDurationSeconds(song.durationText)
                val bucket = byDuration.firstOrNull { members ->
                    members.all { other ->
                        val otherSeconds = parseDurationSeconds(other.durationText)
                        seconds == null || otherSeconds == null ||
                            kotlin.math.abs(seconds - otherSeconds) <= DURATION_TOLERANCE_SECONDS
                    }
                }
                if (bucket == null) byDuration.add(mutableListOf(song))
                else bucket.add(song)
            }
            for (bucket in byDuration) {
                if (bucket.size < 2) continue
                groups.add(
                    DuplicateGroup(
                        songs = bucket.toList(),
                        reason = duplicateReason(bucket),
                        keep = chooseKeep(bucket),
                    ),
                )
            }
        }
        return groups.sortedByDescending { it.songs.size }
    }

    /**
     * Songs with blank titles, artists, or album names. A suggestion is
     * derived from the file name only when it matches `Artist - Title`;
     * otherwise the gap is reported with a null suggestion and no guess is
     * made, because a wrong guess written into metadata is worse than a
     * blank.
     */
    fun findMetadataGaps(songs: List<Song>): List<MetadataGap> {
        val gaps = mutableListOf<MetadataGap>()
        for (song in songs) {
            val fileName = song.localPath?.substringAfterLast('/')
                ?: song.localUri?.substringAfterLast('/')?.substringAfterLast('\\')
            val (fileArtist, fileTitle) = fileName?.let { splitFileName(it) } ?: (null to null)
            if (song.title.isBlank()) {
                gaps.add(MetadataGap(song, "title", fileTitle))
            }
            if (song.artist.isBlank()) {
                gaps.add(MetadataGap(song, "artist", fileArtist))
            }
            if (song.albumName.isNullOrBlank()) {
                // Album is not derivable from a file name; reported, never guessed.
                gaps.add(MetadataGap(song, "album", null))
            }
        }
        return gaps
    }

    /**
     * `Artist - Title.mp3` into its halves, or nulls when the name doesn't
     * carry that shape. The extension is stripped first; a name that is only
     * an id or a bare title yields a title suggestion at most.
     */
    fun splitFileName(fileName: String): Pair<String?, String?> {
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        if (stem.isEmpty()) return null to null
        val idx = stem.indexOf(" - ")
        return if (idx < 0) {
            null to stem.ifEmpty { null }
        } else {
            stem.substring(0, idx).trim().ifEmpty { null } to
                stem.substring(idx + 3).trim().ifEmpty { null }
        }
    }

    /** `m:ss` / `h:mm:ss` into seconds; null when the text says nothing usable. */
    fun parseDurationSeconds(durationText: String?): Int? {
        if (durationText.isNullOrBlank()) return null
        val parts = durationText.trim().split(":").map { it.trim().toIntOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0
        for (part in parts) seconds = seconds * 60 + part
        return seconds
    }

    /** The identity duplicates are found under: normalised title + artist. */
    fun normaliseKey(title: String, artist: String): String =
        M3uPlaylists.normalise(title) + "\u0000" + M3uPlaylists.normalise(artist)

    private fun duplicateReason(bucket: List<Song>): String {
        val durations = bucket.mapNotNull { parseDurationSeconds(it.durationText) }
        return if (durations.size == bucket.size) {
            "Same title, artist and duration (~${durations.first()}s)"
        } else {
            "Same title and artist"
        }
    }

    /**
     * Which copy to keep: a file on the device beats a stream (it plays
     * offline), and among streams the one with the most metadata wins.
     */
    private fun chooseKeep(bucket: List<Song>): Song =
        bucket.sortedWith(
            compareByDescending<Song> { it.localPath != null || it.localUri != null }
                .thenByDescending { metadataScore(it) },
        ).first()

    private fun metadataScore(song: Song): Int {
        var score = 0
        if (!song.thumbnailUrl.isNullOrBlank()) score++
        if (!song.albumName.isNullOrBlank()) score++
        if (song.durationText != null) score++
        if (song.artistId != null) score++
        return score
    }

    /** Two recordings within this many seconds are the same take. */
    const val DURATION_TOLERANCE_SECONDS = 3
}
