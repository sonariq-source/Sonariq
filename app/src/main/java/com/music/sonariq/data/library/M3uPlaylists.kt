package com.music.sonariq.data.library

import com.music.sonariq.data.model.Song

/**
 * m3u playlists in and out, parsed for real.
 *
 * The format read here is extended m3u: an optional `#EXTM3U` header, then
 * per-track pairs of `#EXTINF:<seconds>, <display title>` and a path or URL
 * line. Plain m3u (bare path lines, no EXTINF) reads too. Anything else
 * starting with `#` is a comment and is skipped; blank lines are skipped.
 *
 * Matching an imported entry to a library song is by path first — a
 * `localPath` or `localUri` ending in the entry's path names the same file —
 * and by normalised artist/title second. Matches are reported per entry so
 * the UI can show which lines found a song and which didn't, rather than
 * silently dropping the misses.
 */
object M3uPlaylists {

    /** One line-pair of the playlist: where the track is, and what it is called. */
    data class Entry(
        /** The path or URL line, verbatim. */
        val path: String,
        /** Seconds from the EXTINF line, or null when the file didn't say. */
        val durationSeconds: Int?,
        /** The display title from the EXTINF line, or the path's file name. */
        val title: String,
    )

    /** The outcome of matching one imported entry against the library. */
    sealed interface Match {
        data class Found(val song: Song) : Match
        data class Missing(val entry: Entry) : Match
    }

    /**
     * Parse extended (or plain) m3u text into entries. An EXTINF line always
     * belongs to the path line that follows it; a path line with no EXTINF
     * ahead of it gets its file name as its title.
     */
    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pendingDuration: Int? = null
        var pendingTitle: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    // Case-insensitive prefix strip without mangling the body.
                    val body = line.substring(line.indexOf(':') + 1)
                    pendingDuration = body.substringBefore(',').trim().toIntOrNull()
                    pendingTitle = body.substringAfter(',', "").trim().ifEmpty { null }
                }
                // #EXTM3U and every other # line: a comment, skipped.
                continue
            }
            val title = pendingTitle ?: fileNameOf(line)
            entries.add(Entry(path = line, durationSeconds = pendingDuration, title = title))
            pendingDuration = null
            pendingTitle = null
        }
        return entries
    }

    /**
     * Write songs as extended m3u. Each track gets an EXTINF line with its
     * duration in seconds and `Artist - Title`, then the path line: the real
     * file path when the song has one ([Song.localPath] first, then
     * [Song.localUri]), falling back to the video id as an identifier other
     * tools can't resolve but this app's own import can match back by title.
     */
    fun build(songs: List<Song>): String {
        val out = StringBuilder("#EXTM3U\n")
        for (song in songs) {
            val seconds = LibraryCleanup.parseDurationSeconds(song.durationText) ?: -1
            val display = if (song.artist.isBlank()) song.title else "${song.artist} - ${song.title}"
            val path = song.localPath?.takeIf { it.isNotBlank() }
                ?: song.localUri?.takeIf { it.isNotBlank() }
                ?: song.videoId
            out.append("#EXTINF:").append(seconds).append(',').append(display).append('\n')
            out.append(path).append('\n')
        }
        return out.toString()
    }

    /** Match every entry against [songs], reporting misses rather than dropping them. */
    fun matchAll(entries: List<Entry>, songs: List<Song>): List<Match> =
        entries.map { entry ->
            matchEntry(entry, songs)?.let { Match.Found(it) } ?: Match.Missing(entry)
        }

    /**
     * One entry against the library: path match first, normalised
     * artist/title second. A path matches when a song's path ends with the
     * entry's path, so an absolute export still matches a file that moved
     * folders.
     */
    fun matchEntry(entry: Entry, songs: List<Song>): Song? {
        val entryPath = entry.path.trim()
        if (entryPath.isNotEmpty()) {
            val normEntry = normalisePath(entryPath)
            songs.firstOrNull { song ->
                val local = song.localPath ?: song.localUri ?: return@firstOrNull false
                normalisePath(local).endsWith(normEntry)
            }?.let { return it }
        }
        // "Artist - Title" split of the display title; a bare title matches
        // the title alone.
        val (artist, title) = splitArtistTitle(entry.title)
        val normTitle = normalise(title)
        if (normTitle.isEmpty()) return null
        return songs.firstOrNull { song ->
            normalise(song.title) == normTitle &&
                (artist == null || normalise(song.artist) == normalise(artist))
        }
    }

    /** `Artist - Title` into its halves; null artist when there is no separator. */
    fun splitArtistTitle(display: String): Pair<String?, String> {
        val idx = display.indexOf(" - ")
        return if (idx < 0) null to display.trim()
        else display.substring(0, idx).trim().ifEmpty { null } to display.substring(idx + 3).trim()
    }

    /**
     * Lowercase, punctuation and whitespace stripped — the comparison that
     * lets `Don't Stop Me Now`, `don't stop me now` and
     * `Don't  Stop  Me Now!` be the same track.
     */
    fun normalise(text: String): String = text.lowercase()
        .replace(Regex("[\\p{P}\\p{S}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalisePath(path: String): String =
        path.replace('\\', '/').trim().trim('/').lowercase()

    private fun fileNameOf(path: String): String =
        path.replace('\\', '/').substringAfterLast('/').trim()
}
