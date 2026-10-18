package com.music.sonariq.ui.discovery

/**
 * Route ids for the discovery screens, for Phase 2 navigation wiring.
 *
 * Kept as plain constants so the host's navigator can switch on them without
 * importing the screens themselves. Arguments ride in the host's usual way:
 * [COVER_MAKER] takes the playlist browse id (and its title for the
 * initial cover text).
 */
object DiscoveryRoutes {
    /** Listening insights: streaks, genres, top tracks/artists, all shareable. */
    const val INSIGHTS = "discovery/insights"

    /** New-release radar: followed artists' latest releases + new releases. */
    const val RADAR = "discovery/radar"

    /** Playlist cover maker. Args: browseId, title. */
    const val COVER_MAKER = "discovery/cover"

    /** Monthly recap: one month of listening, shareable as a card. */
    const val RECAP = "discovery/recap"
}
