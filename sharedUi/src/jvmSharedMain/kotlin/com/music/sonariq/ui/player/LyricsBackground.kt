package com.music.sonariq.ui.player

/**
 * What the lyrics panel draws behind its lines, picked in the panel's
 * appearance menu and in Settings.
 *
 * - [ARTWORK] — nothing of the panel's own: the player's artwork backdrop
 *   shows through, as it always has.
 * - [DIMMED] — a translucent dark scrim over that backdrop, for artwork
 *   bright enough to fight the words.
 * - [SOLID] — the plain app background, artwork hidden entirely: the
 *   calmest read, and the cheapest frame of the three.
 */
enum class LyricsBackground {
    ARTWORK,
    DIMMED,
    SOLID;

    /**
     * The stable persisted name. Never rename an entry without migrating
     * the values stored under the old name.
     */
    val persistedName: String get() = name.lowercase()

    companion object {
        /**
         * Reads a persisted name back, defaulting to [ARTWORK] for anything
         * unrecognised — including the blank of a fresh install.
         */
        fun fromPersistedName(name: String?): LyricsBackground =
            entries.firstOrNull { it.persistedName == name } ?: ARTWORK
    }
}
