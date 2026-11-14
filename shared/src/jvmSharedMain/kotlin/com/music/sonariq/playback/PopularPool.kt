package com.music.sonariq.playback

import com.music.sonariq.data.model.Song

/**
 * The popular songs AutoPlay draws on: a country's charts, in chart
 * order, with the global charts behind them.
 *
 * A chart is an ordered list, and the order is the evidence — [ranks]
 * is each song's best (lowest) position across the pools that carried
 * it, and [songs] is those songs in popularity order: the country's
 * chart first, then the global chart's songs the country chart did not
 * already name, ranked behind every country entry. When a candidate
 * also sits in the discovery pool its rank still applies — the rank
 * belongs to the song, not to the source that surfaced it.
 */
data class PopularPool(
    val songs: List<Song>,
    val ranks: Map<String, Int>,
) {
    companion object {
        val EMPTY = PopularPool(emptyList(), emptyMap())
    }
}

/**
 * Composes the country and global chart lists into one [PopularPool].
 * Either may be empty: no country chart (or no country) leaves the
 * global chart ranked from the top, and no charts at all is an empty
 * pool, which AutoPlay reads as "discovery only, as before".
 */
fun mergePopularPools(
    countryPool: List<Song>,
    globalPool: List<Song>,
): PopularPool {
    val songs = ArrayList<Song>(countryPool.size + globalPool.size)
    val ranks = HashMap<String, Int>()
    for (song in countryPool) {
        if (song.videoId.isBlank() || song.videoId in ranks) continue
        ranks[song.videoId] = songs.size
        songs += song
    }
    for (song in globalPool) {
        if (song.videoId.isBlank() || song.videoId in ranks) continue
        ranks[song.videoId] = songs.size
        songs += song
    }
    return PopularPool(songs, ranks)
}
