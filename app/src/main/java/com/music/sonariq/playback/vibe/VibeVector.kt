package com.music.sonariq.playback.vibe

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VibeVector: a 10-dimensional mood fingerprint for a track.
 *
 * Each dimension is a weight in [0..1] describing how strongly the track
 * expresses that mood. Vectors are learned from sources like Last.fm tags
 * and refined by playback feedback, then compared with cosine similarity to
 * build autoplay queues that stay in the right mood.
 */
@Serializable
data class VibeVector(
    val sad: Float = 0f,
    val happy: Float = 0f,
    val chill: Float = 0f,
    val energetic: Float = 0f,
    val angry: Float = 0f,
    val romantic: Float = 0f,
    val nostalgic: Float = 0f,
    val party: Float = 0f,
    val focus: Float = 0f,
    val workout: Float = 0f,
) {
    private fun components(): FloatArray =
        floatArrayOf(sad, happy, chill, energetic, angry, romantic, nostalgic, party, focus, workout)

    /**
     * Cosine similarity between this vector and [other], in [0..1].
     * Returns 0 when either vector is empty.
     */
    fun cosineSimilarity(other: VibeVector): Float {
        val a = components()
        val b = other.components()
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 0f) return 0f
        return min(1f, max(0f, dot / denom))
    }

    /**
     * Name of the vibe with the highest weight ("sad", "happy", ...),
     * or "unknown" when every dimension is 0.
     */
    fun dominant(): String {
        val entries = listOf(
            "sad" to sad,
            "happy" to happy,
            "chill" to chill,
            "energetic" to energetic,
            "angry" to angry,
            "romantic" to romantic,
            "nostalgic" to nostalgic,
            "party" to party,
            "focus" to focus,
            "workout" to workout,
        )
        val best = entries.maxByOrNull { it.second } ?: return "unknown"
        return if (best.second <= 0f) "unknown" else best.first
    }

    /** True when every dimension is 0. */
    fun isEmpty(): Boolean = components().all { it == 0f }

    /** Component-wise addition, for blending multiple vectors. */
    operator fun plus(other: VibeVector): VibeVector = VibeVector(
        sad = sad + other.sad,
        happy = happy + other.happy,
        chill = chill + other.chill,
        energetic = energetic + other.energetic,
        angry = angry + other.angry,
        romantic = romantic + other.romantic,
        nostalgic = nostalgic + other.nostalgic,
        party = party + other.party,
        focus = focus + other.focus,
        workout = workout + other.workout,
    )

    /** Scales every dimension by [weight], for weighted combining. */
    operator fun times(weight: Float): VibeVector = VibeVector(
        sad = sad * weight,
        happy = happy * weight,
        chill = chill * weight,
        energetic = energetic * weight,
        angry = angry * weight,
        romantic = romantic * weight,
        nostalgic = nostalgic * weight,
        party = party * weight,
        focus = focus * weight,
        workout = workout * weight,
    )
}
