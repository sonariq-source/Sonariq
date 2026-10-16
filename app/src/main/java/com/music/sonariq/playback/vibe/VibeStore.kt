package com.music.sonariq.playback.vibe

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * VibeStore: persistent per-track vibe vectors for vibe-aware autoplay.
 *
 * Follows the SmartAutoplay pattern — compact JSON in SharedPreferences.
 * Each track keeps its learned [VibeVector] plus a confidence score, and
 * [recordVibeFeedback] nudges the vector toward/away from a session's vibe
 * when the user reacts (skip vs. like), forming the learning loop.
 */
@Serializable
data class StoredVibe(
    val vector: VibeVector,
    val confidence: Float,
    val updatedAt: Long,
)

private const val PREFS_NAME = "vibe_store"
private const val KEY_VIBES = "vibes"
private const val MAX_VIBES = 5000

/** How much a single feedback event moves the stored vector (0..1). */
private const val FEEDBACK_LEARNING_RATE = 0.15f

object VibeStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parseVibes(raw: String?): MutableMap<String, StoredVibe> =
        runCatching {
            json.decodeFromString<Map<String, StoredVibe>>(raw ?: "{}").toMutableMap()
        }.getOrDefault(mutableMapOf())

    private fun writeVibes(store: SharedPreferences, vibes: MutableMap<String, StoredVibe>) {
        store.edit().putString(KEY_VIBES, json.encodeToString(vibes)).apply()
    }

    /** Returns the stored vibe for [videoId], or null if the track has none yet. */
    fun getVibe(context: Context, videoId: String): StoredVibe? {
        val store = prefs(context)
        return parseVibes(store.getString(KEY_VIBES, null))[videoId]
    }

    /** Saves (or replaces) the vibe vector for [videoId], capped to [MAX_VIBES] entries. */
    fun saveVibe(context: Context, videoId: String, vector: VibeVector, confidence: Float) {
        val store = prefs(context)
        val vibes = parseVibes(store.getString(KEY_VIBES, null))
        vibes[videoId] = StoredVibe(
            vector = vector,
            confidence = confidence.coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
        )
        evictOldest(vibes)
        writeVibes(store, vibes)
    }

    /**
     * Learning loop: adjusts the stored vector for [videoId] slightly
     * toward [sessionVibe] when [positive] (e.g. the user liked it and it
     * fit the session), or slightly away when negative (e.g. skipped it).
     * Confidence drifts up on positive feedback, down on negative.
     * Seeds a fresh entry from [sessionVibe] when none exists.
     */
    fun recordVibeFeedback(
        context: Context,
        videoId: String,
        sessionVibe: VibeVector,
        positive: Boolean,
    ) {
        val store = prefs(context)
        val vibes = parseVibes(store.getString(KEY_VIBES, null))
        val now = System.currentTimeMillis()
        val existing = vibes[videoId]
        val updated = if (existing == null) {
            // Seed from the session vibe with low confidence.
            StoredVibe(
                vector = sessionVibe * FEEDBACK_LEARNING_RATE,
                confidence = if (positive) 0.2f else 0.05f,
                updatedAt = now,
            )
        } else {
            val rate = FEEDBACK_LEARNING_RATE
            val moved = if (positive) {
                // Nudge toward the session vibe.
                (existing.vector * (1f - rate)) + (sessionVibe * rate)
            } else {
                // Nudge away from the session vibe.
                (existing.vector * (1f + rate)) + (sessionVibe * -rate)
            }
            val clamped = moved.copy(
                sad = max(0f, moved.sad),
                happy = max(0f, moved.happy),
                chill = max(0f, moved.chill),
                energetic = max(0f, moved.energetic),
                angry = max(0f, moved.angry),
                romantic = max(0f, moved.romantic),
                nostalgic = max(0f, moved.nostalgic),
                party = max(0f, moved.party),
                focus = max(0f, moved.focus),
                workout = max(0f, moved.workout),
            )
            StoredVibe(
                vector = clamped,
                confidence = (existing.confidence + if (positive) rate else -rate).coerceIn(0f, 1f),
                updatedAt = now,
            )
        }
        vibes[videoId] = updated
        evictOldest(vibes)
        writeVibes(store, vibes)
    }

    /** Drops the oldest entries by updatedAt so the store stays bounded. */
    private fun evictOldest(vibes: MutableMap<String, StoredVibe>) {
        if (vibes.size <= MAX_VIBES) return
        vibes.entries
            .sortedBy { it.value.updatedAt }
            .take(vibes.size - MAX_VIBES)
            .forEach { vibes.remove(it.key) }
    }
}
