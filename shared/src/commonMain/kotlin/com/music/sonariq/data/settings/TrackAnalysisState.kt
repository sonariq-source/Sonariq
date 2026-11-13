package com.music.sonariq.data.settings

/*
 * Shared between the Android and desktop players: both run the same analyser
 * and both report the same states from it, so the vocabulary lives in one
 * place. The package is unchanged, so neither side needed a new import.
 */

/** Where one track stands in Automix's analysis. */
enum class TrackAnalysisState {
    /** Nothing in flight and no result — usually waiting on bytes to arrive. */
    WAITING,

    /** Decode and inference running now; a result is a few seconds away. */
    ANALYSING,

    /** Measured, with a tempo the planner can actually use. */
    ANALYSED,

    /**
     * Measured off the track's opening, with the whole-track pass running now to replace those
     * numbers with better ones.
     */
    REFINING,

    /** Tried and came back with nothing usable — a decode error, or audio that yielded no tempo. */
    FAILED,
}

/**
 * The method of the transition planned into the next track, in the honest
 * words the stats line uses. Only ever set from a real plan: a method is
 * claimed only when the planner actually chose it, and the fallback
 * crossfade says so.
 */
enum class TransitionMethod {
    /** Beat grids aligned and blended on the shared grid. */
    BEATMATCHED,

    /** Keys carried a transition the tempi could not. */
    HARMONIC_BLEND,

    /** The two were kept apart spectrally and vocally rather than blended. */
    INSTRUMENTAL,

    /** A calm pair given a long, smooth blend. */
    AMBIENT,

    /** The conventional equal-power crossfade. */
    STANDARD_CROSSFADE,
}

/** Both sides of the next transition, for stats for nerds. */
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
    /** The planned transition's method, or null when no plan describes this pair. */
    val transitionMethod: TransitionMethod? = null,
    /** Measured tempi of the pair, 0 when either side has none. */
    val currentBpm: Double = 0.0,
    val nextBpm: Double = 0.0,
    /** Measured keys of the pair, blank when either side has none it trusts. */
    val currentKey: String = "",
    val nextKey: String = "",
    /** Whether the two trusted keys are Camelot-compatible; null when either is unknown. */
    val keysCompatible: Boolean? = null,
)

/**
 * A span of the playing track, in fractions of its duration, that the next transition is planned to
 * occupy.
 */
data class TransitionWindow(val start: Float, val end: Float)

/**
 * An Automix blend in flight, as the scrubber draws it: the beat it is running
 * on, and whether it is moving. Only republished when one of those changes.
 */
data class MixBlend(
    /**
     * Wall-clock length of one beat at the tempo the blend is actually playing
     * — after any beatmatch stretch and the listener's own speed — or 0 when
     * neither track's tempo is known.
     */
    val beatMs: Float,
    /** A `System.nanoTime()` at which a beat landed; with [beatMs], places every other one. */
    val beatAnchorNanos: Long,
    /** Whether the blend is playing rather than paused; a paused blend holds still. */
    val playing: Boolean,
)
