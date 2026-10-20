package com.music.sonariq.playback

/**
 * AutoplaySession: session-level energy intent for autoplay.
 *
 * Written by the session engine — the energy curve, workout and wind-down
 * modes and activity detection each say where the session's energy should
 * sit ([targetEnergy]) and how hard autoplay should lean towards it
 * ([energyWeight]).
 *
 * [familiarOnly] narrows the field instead: while the listener is driving,
 * autoplay serves only tracks already in their listening history.
 *
 * Read by vibe scoring in
 * [VibeAutoplay][com.music.sonariq.playback.vibe.VibeAutoplay]: a null
 * target means no shaping at all and scoring behaves exactly as it did
 * before energy shaping existed.
 */
object AutoplaySession {

    /** The energy this session is steering towards (0..1), or null for none. */
    @Volatile
    var targetEnergy: Float? = null

    /** How strongly the target energy pulls the vibe score (0..1). */
    @Volatile
    var energyWeight: Float = 0.35f

    /**
     * When true, vibe autoplay only serves tracks the listener has actually
     * played before (plays > 0). Written by the session engine while the
     * activity monitor reports driving — a familiar soundtrack for the
     * road, nothing the listener would reach for the screen to skip.
     * False everywhere else.
     */
    @Volatile
    var familiarOnly: Boolean = false
}
