package com.music.sonariq.ui.home

import com.music.sonariq.data.model.HomeShelf
import com.music.sonariq.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Home sections that learn: the order of the home feed's sections follows what
 * the listener actually plays, unless the listener dragged them into their own
 * order — an explicit manual order always wins.
 *
 * The screen itself ([HomeScreen][com.music.sonariq.ui.screens.HomeScreen]) is
 * untouched: Phase 2 sorts the shelves with [rankSections] before handing them
 * over, so only the *order* of the existing sections changes, never their
 * layout. The manual order is persisted in [AppSettings.homeSectionOrderJson];
 * the behaviour-driven order needs no persistence — it is recomputed from the
 * listening stats every time the home feed loads.
 *
 * A section's identity is its [sectionId]: the feed browse id when the feed
 * carries one, otherwise the normalised title. Both survive feed refreshes;
 * a section the feed no longer serves simply never matches an id.
 */
object HomeSectionRanking {

    /** What a section is, for the behavioural ranking. */
    enum class HomeSectionKind {
        RECENTS,
        PLAYLISTS,
        MOOD,
        NEW_RELEASES,
        CHARTS,
        ARTIST,
        OTHER,
    }

    /**
     * Stable identity for a shelf across feed refreshes. Prefers the feed's own
     * browse id; falls back to the lower-cased title, which the feed keeps
     * stable for its standing sections.
     */
    fun sectionId(shelf: HomeShelf): String {
        val browse = shelf.moreBrowseId?.trim().orEmpty()
        if (browse.isNotEmpty()) return "browse:$browse"
        return "title:" + shelf.title.trim().lowercase()
    }

    /**
     * Which kind a shelf is, off its title and browse id. YouTube's standing
     * sections keep English titles in every locale the parser recognises; the
     * browse-id prefixes cover the cases where they don't.
     */
    fun classify(shelf: HomeShelf): HomeSectionKind {
        val browse = shelf.moreBrowseId.orEmpty()
        val title = shelf.title.lowercase()
        if (browse.startsWith("FEmusic_new_releases") || browse.startsWith("FEmusic_explore") ||
            title.contains("new album") || title.contains("new single") || title.contains("new release")
        ) return HomeSectionKind.NEW_RELEASES
        if (title.contains("listen again") || title.contains("recent") || title.contains("jump back in") ||
            title.contains("on repeat")
        ) return HomeSectionKind.RECENTS
        if (title.contains("mood") || title.contains("genre") || title.contains("vibe") ||
            title.contains("morning") || title.contains("evening") || title.contains("workout")
        ) return HomeSectionKind.MOOD
        if (title.contains("top ") || title.contains("chart") || title.contains("trending")
        ) return HomeSectionKind.CHARTS
        if (shelf.moreBrowseId?.startsWith("UC") == true || shelf.moreBrowseId?.startsWith("MPAD") == true ||
            title.contains("from ") && title.contains("artist")
        ) return HomeSectionKind.ARTIST
        if (title.contains("playlist") || browse == "playlists_shelf") return HomeSectionKind.PLAYLISTS
        return HomeSectionKind.OTHER
    }

    /**
     * How much a shelf matches the listener's recent top artists: the share of
     * its items whose byline names one. 0.0 when there is nothing to match
     * against or nothing matches — sections never float on a hunch.
     */
    fun artistAffinity(shelf: HomeShelf, recentArtists: Set<String>): Double {
        if (recentArtists.isEmpty() || shelf.items.isEmpty()) return 0.0
        val lowered = recentArtists.map { it.lowercase() }.toSet()
        val hits = shelf.items.count { item ->
            val byline = item.subtitle.lowercase()
            byline.isNotBlank() && lowered.any { it.isNotBlank() && byline.contains(it) }
        }
        return hits.toDouble() / shelf.items.size
    }

    /**
     * The home feed's sections in display order.
     *
     * @param shelves the feed's sections, in the order the feed served them.
     * @param recentArtists the listener's recent top artists (from the Replay
     * stats), lower-cased or not — matching is case-insensitive.
     * @param manualOrder section ids the listener dragged into place, most
     * recent drag last; ids the feed no longer serves are ignored.
     * @param learn whether behaviour reorders at all. Off means the manual
     * order (then the feed order) is the whole story.
     *
     * Manual ids keep their relative order at the top. Everything else follows
     * behaviour: recents and the listener's own playlists surface early,
     * artist-matching sections climb with [artistAffinity], and anything the
     * ranking knows nothing about keeps the feed's order rather than being
     * shuffled at random.
     */
    fun rankSections(
        shelves: List<HomeShelf>,
        recentArtists: Set<String>,
        manualOrder: List<String>,
        learn: Boolean,
    ): List<HomeShelf> {
        if (shelves.isEmpty()) return shelves
        val byId = shelves.associateBy(::sectionId)
        val manualIds = manualOrder.filter { it in byId }.distinct()
        val manual = manualIds.mapNotNull { byId[it] }
        val rest = shelves.filter { it !in manual.toSet() }
        if (!learn || rest.isEmpty()) return manual + rest

        val scored = rest.mapIndexed { index, shelf ->
            val kind = classify(shelf)
            val kindBoost = when (kind) {
                HomeSectionKind.RECENTS -> 2.0
                HomeSectionKind.PLAYLISTS -> 1.5
                HomeSectionKind.MOOD -> 1.0
                HomeSectionKind.NEW_RELEASES -> 0.6
                HomeSectionKind.ARTIST -> 0.4
                HomeSectionKind.CHARTS -> 0.3
                HomeSectionKind.OTHER -> 0.0
            }
            val score = kindBoost + artistAffinity(shelf, recentArtists) * 2.0
            Triple(shelf, score, index)
        }
        // Stable: equal scores keep the feed's order.
        val ordered = scored.sortedWith(
            compareByDescending<Triple<HomeShelf, Double, Int>> { it.second }
                .thenBy { it.third },
        ).map { it.first }
        return manual + ordered
    }

    /**
     * Moves one section id inside a manual order list. Pure, so the reorder
     * sheet and its tests share the exact arithmetic.
     */
    fun moveId(order: List<String>, fromId: String, toIndex: Int): List<String> {
        val from = order.indexOf(fromId)
        if (from < 0) return order
        val mutable = order.toMutableList()
        mutable.removeAt(from)
        mutable.add(toIndex.coerceIn(0, mutable.size), fromId)
        return mutable
    }

    /** Serialises a manual order for [AppSettings.homeSectionOrderJson]. */
    fun encodeOrder(ids: List<String>): String = ids.joinToString("\n")

    /** Parses [encodeOrder]'s output; garbage in, empty order out. */
    fun decodeOrder(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * The persisted manual order. Lives behind [AppSettings.homeSectionOrderJson]
 * (a String holding [HomeSectionRanking.encodeOrder]'s output); this store is
 * the only writer, so the sheet and the ranking never disagree about the
 * format.
 *
 * New keys (Phase 2 adds to AppSettings, same pattern as the existing
 * settings):
 * - `homeSectionOrderJson`: String, default `""`
 * - `homeSectionLearningEnabled`: Boolean, default `true`
 */
object HomeSectionOrderStore {

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    /** Call once at startup, after AppSettings.init. */
    fun load() {
        _order.value = HomeSectionRanking.decodeOrder(AppSettings.homeSectionOrderJson.value)
    }

    fun save(ids: List<String>) {
        val distinct = ids.distinct()
        _order.value = distinct
        AppSettings.homeSectionOrderJson.value = HomeSectionRanking.encodeOrder(distinct)
    }

    fun move(fromId: String, toIndex: Int) {
        save(HomeSectionRanking.moveId(_order.value, fromId, toIndex))
    }

    fun reset() {
        save(emptyList())
    }
}
