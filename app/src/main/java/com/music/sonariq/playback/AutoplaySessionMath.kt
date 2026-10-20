package com.music.sonariq.playback

/**
 * SessionEnergy: the pure arithmetic of the session engine — how an energy
 * target (0..1) moves over a listening session. No Android, no settings, no
 * clocks: every function takes its progress as an argument and answers, so
 * the curves stay unit-testable without a device. [PlaybackService]'s
 * session engine works out the progress from real signals (wall clock,
 * workout start, track count) and turns the answers into
 * [AutoplaySession.targetEnergy].
 */
object SessionEnergy {

    /**
     * The target for one energy-curve preset after [tracksPlayed] tracks.
     *
     * Presets mirror `AppSettings.energyCurve`:
     * - 0 Steady — no curve at all (null: no shaping).
     * - 1 Build — starts mellow and climbs by 0.05 a track to a 0.85 ceiling.
     * - 2 Peak & Coast — rises from 0.5 to a 0.9 peak over the first six
     *   tracks, holds it through track eleven, then coasts down by 0.05 a
     *   track, never below 0.4.
     * - 3 Wind Down — starts at 0.7 and eases down by 0.04 a track, never
     *   below 0.2.
     *
     * Any other preset value has no honest curve, so it answers null.
     */
    fun curveTarget(preset: Int, tracksPlayed: Int): Float? = when (preset) {
        0 -> null
        1 -> minOf(0.85f, 0.35f + 0.05f * tracksPlayed)
        2 -> when {
            tracksPlayed < 6 -> 0.5f + 0.4f * (tracksPlayed / 6f)
            tracksPlayed <= 11 -> 0.9f
            else -> maxOf(0.4f, 0.9f - 0.05f * (tracksPlayed - 11))
        }
        3 -> maxOf(0.2f, 0.7f - 0.04f * tracksPlayed)
        else -> null
    }

    /**
     * The target at [progress] (0..1) through a workout session: a warm-up
     * ramp from 0.45 to 0.6 over the first 15%, a climb to 0.9 by 75%, a
     * plateau at 0.9 until 90%, then a cool-down fall to 0.45 at the end.
     */
    fun workoutTarget(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < 0.15f -> 0.45f + 0.15f * (p / 0.15f)
            p < 0.75f -> 0.6f + 0.3f * ((p - 0.15f) / 0.6f)
            p < 0.9f -> 0.9f
            else -> 0.9f - 0.45f * ((p - 0.9f) / 0.1f)
        }
    }

    /**
     * The target at [progress] (0..1) through the wind-down window before
     * bedtime: a straight glide from 0.6 at the window's start down to 0.15
     * at bedtime itself.
     */
    fun windDownTarget(progress: Float): Float =
        0.6f - 0.45f * progress.coerceIn(0f, 1f)
}
