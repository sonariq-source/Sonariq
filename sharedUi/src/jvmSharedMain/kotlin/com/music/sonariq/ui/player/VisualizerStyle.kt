package com.music.sonariq.ui.player

/**
 * The Now Playing visualizer's rendering mode, picked in Settings.
 *
 * The strip is drawn from the player session's own audio capture
 * (android.media.audiofx.Visualizer) — no microphone, no recording. [OFF]
 * hides the strip entirely and the capture with it.
 *
 * Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */
enum class VisualizerStyle {
    OFF,
    SPECTRUM,
    WAVEFORM,
    CIRCULAR;

    /**
     * The stable persisted name. Never rename an entry without migrating
     * [AppSettings][com.music.sonariq.data.settings.AppSettings] values
     * stored under the old name.
     */
    val persistedName: String get() = name.lowercase()

    companion object {
        /**
         * Reads a persisted name back, defaulting to [SPECTRUM] for anything
         * unrecognised — including the blank of a fresh install.
         */
        fun fromPersistedName(name: String?): VisualizerStyle =
            entries.firstOrNull { it.persistedName == name } ?: SPECTRUM
    }
}
