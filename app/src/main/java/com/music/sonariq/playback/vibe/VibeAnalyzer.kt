package com.music.sonariq.playback.vibe

import com.music.sonariq.playback.smart.EnergySample
import com.music.sonariq.playback.smart.TrackAnalysis

/**
 * VibeAnalyzer: derives a track's [VibeVector] from every real signal available.
 *
 * Three signals feed the final vector, each with its own trust weight:
 * - Last.fm tags mapped to vibe space ([VibeAnalyzer.TAG_WEIGHT])
 * - DSP audio analysis — tempo, key, and energy curve ([VibeAnalyzer.AUDIO_WEIGHT])
 * - Learned user behavior ([VibeAnalyzer.BEHAVIOR_WEIGHT])
 *
 * The returned confidence (0..1) is high when signals agree and low when they
 * are missing or contradict each other; low confidence is the trigger for
 * re-analysis elsewhere in the vibe pipeline.
 *
 * Pure Kotlin, no Android dependencies.
 */
object VibeAnalyzer {
    /** Trust weight for the Last.fm tag signal. */
    const val TAG_WEIGHT = 0.5f

    /** Trust weight for the DSP audio-analysis signal. */
    const val AUDIO_WEIGHT = 0.3f

    /** Trust weight for the learned user-behavior signal. */
    const val BEHAVIOR_WEIGHT = 0.2f

    // Scratch-array indices for fromAudio, in VibeVector constructor order.
    private const val SAD = 0
    private const val HAPPY = 1
    private const val CHILL = 2
    private const val ENERGETIC = 3
    private const val ANGRY = 4
    private const val ROMANTIC = 5
    private const val NOSTALGIC = 6
    private const val PARTY = 7
    private const val FOCUS = 8
    private const val WORKOUT = 9

    /**
     * Computes the vibe vector for a track from all available signals.
     *
     * The vector is a weighted average over the signals that actually produced
     * evidence (weights renormalized), so its scale stays comparable whether
     * one signal or all three are present. Missing or empty signals contribute
     * nothing.
     *
     * Confidence starts at 0.5, gains +0.2 for non-empty tags, +0.15 for usable
     * audio analysis, +0.1 for non-empty behavior, and loses 0.2 when two or
     * more signals are present but their average pairwise cosine similarity is
     * below 0.4 (signals disagree). Clamped to 0..1.
     */
    fun analyze(
        tags: VibeVector,
        analysis: TrackAnalysis?,
        behaviorVibe: VibeVector,
    ): Pair<VibeVector, Float> {
        val usableAnalysis = analysis?.takeIf { it.isUsable }
        val audioUsable = usableAnalysis != null
        val audioVibe = if (usableAnalysis != null) fromAudio(usableAnalysis) else VibeVector()

        val tagsPresent = !tags.isEmpty()
        val audioPresent = audioUsable && !audioVibe.isEmpty()
        val behaviorPresent = !behaviorVibe.isEmpty()

        var combined = VibeVector()
        var weightSum = 0f
        if (tagsPresent) {
            combined = combined + tags * TAG_WEIGHT
            weightSum += TAG_WEIGHT
        }
        if (audioPresent) {
            combined = combined + audioVibe * AUDIO_WEIGHT
            weightSum += AUDIO_WEIGHT
        }
        if (behaviorPresent) {
            combined = combined + behaviorVibe * BEHAVIOR_WEIGHT
            weightSum += BEHAVIOR_WEIGHT
        }
        if (weightSum > 0f) combined = combined * (1f / weightSum)

        var confidence = 0.5f
        if (tagsPresent) confidence += 0.2f
        if (audioUsable) confidence += 0.15f
        if (behaviorPresent) confidence += 0.1f

        val signals = listOfNotNull(
            tags.takeIf { tagsPresent },
            audioVibe.takeIf { audioPresent },
            behaviorVibe.takeIf { behaviorPresent },
        )
        if (signals.size >= 2) {
            var agreementSum = 0f
            var pairs = 0
            for (i in signals.indices) {
                for (j in i + 1 until signals.size) {
                    agreementSum += signals[i].cosineSimilarity(signals[j])
                    pairs++
                }
            }
            if (pairs > 0 && agreementSum / pairs < 0.4f) confidence -= 0.2f
        }

        return combined to confidence.coerceIn(0f, 1f)
    }

    /**
     * Derives a vibe vector purely from DSP audio analysis.
     *
     * Tempo contributions are scaled by [TrackAnalysis.beatConfidence], key
     * contributions by [TrackAnalysis.keyConfidence], and energy is placed on
     * 0..1 by min-max normalizing the energy curve itself (0.5 = neutral when
     * the curve is empty or constant). The result is normalized so its maximum
     * dimension is 1.0. Returns an empty vector when the analysis is not usable.
     */
    fun fromAudio(analysis: TrackAnalysis): VibeVector {
        if (!analysis.isUsable) return VibeVector()

        val a = FloatArray(10)

        // Tempo -> mood lean, scaled by how far the beat grid can be trusted.
        val tempoScale = analysis.beatConfidence.toFloat().coerceIn(0f, 1f)
        when {
            analysis.bpm < 70 -> {
                a[SAD] += 0.8f * tempoScale
                a[CHILL] += 0.5f * tempoScale
                a[ROMANTIC] += 0.3f * tempoScale
            }
            analysis.bpm < 95 -> {
                a[CHILL] += 0.7f * tempoScale
                a[SAD] += 0.4f * tempoScale
                a[ROMANTIC] += 0.4f * tempoScale
                a[NOSTALGIC] += 0.2f * tempoScale
            }
            analysis.bpm < 115 -> {
                a[HAPPY] += 0.5f * tempoScale
                a[CHILL] += 0.3f * tempoScale
                a[ROMANTIC] += 0.3f * tempoScale
                a[FOCUS] += 0.3f * tempoScale
            }
            analysis.bpm < 135 -> {
                a[HAPPY] += 0.7f * tempoScale
                a[ENERGETIC] += 0.6f * tempoScale
                a[PARTY] += 0.4f * tempoScale
            }
            else -> {
                a[ENERGETIC] += 0.8f * tempoScale
                a[PARTY] += 0.7f * tempoScale
                a[WORKOUT] += 0.7f * tempoScale
            }
        }

        // Key -> mood lean, scaled by key-detection confidence.
        // Standard notation: minor keys end in "m" ("Am"), major keys do not ("C").
        val keyScale = analysis.keyConfidence.toFloat().coerceIn(0f, 1f)
        when {
            analysis.key.trim().isEmpty() -> Unit
            analysis.key.trim().endsWith("m", ignoreCase = true) -> {
                a[SAD] += 0.6f * keyScale
                a[ANGRY] += 0.3f * keyScale
                a[ROMANTIC] += 0.3f * keyScale
            }
            else -> {
                a[HAPPY] += 0.5f * keyScale
                a[PARTY] += 0.3f * keyScale
            }
        }

        // Mean energy of the normalized curve -> mood lean.
        val energy = normalizedEnergy(analysis.energyCurve)
        if (energy < 0.35f) {
            a[SAD] += 0.4f
            a[CHILL] += 0.5f
            a[ROMANTIC] += 0.3f
            a[FOCUS] += 0.3f
        } else if (energy > 0.65f) {
            a[ENERGETIC] += 0.5f
            a[PARTY] += 0.4f
            a[WORKOUT] += 0.4f
            a[ANGRY] += 0.3f
        }

        val max = a.maxOrNull() ?: 0f
        if (max <= 0f) return VibeVector()
        return VibeVector(
            sad = a[SAD] / max,
            happy = a[HAPPY] / max,
            chill = a[CHILL] / max,
            energetic = a[ENERGETIC] / max,
            angry = a[ANGRY] / max,
            romantic = a[ROMANTIC] / max,
            nostalgic = a[NOSTALGIC] / max,
            party = a[PARTY] / max,
            focus = a[FOCUS] / max,
            workout = a[WORKOUT] / max,
        )
    }

    /**
     * Session vibe: the weighted average of the last played songs' vectors,
     * with linear weights favoring the most recent (oldest gets weight 1,
     * newest gets weight N). Used as the "current vibe" for autoplay.
     * Returns an empty vector for empty input.
     */
    fun sessionVibe(recent: List<VibeVector>): VibeVector {
        if (recent.isEmpty()) return VibeVector()
        var combined = VibeVector()
        var weightSum = 0f
        recent.forEachIndexed { index, vibe ->
            val weight = (index + 1).toFloat()
            combined = combined + vibe * weight
            weightSum += weight
        }
        return combined * (1f / weightSum)
    }

    /**
     * Places the mean energy of [curve] on 0..1 by min-max normalizing the
     * curve itself first (the raw scale is unknown). Returns 0.5 (neutral)
     * when the curve is empty, constant, or has no finite samples.
     */
    private fun normalizedEnergy(curve: List<EnergySample>): Float {
        if (curve.isEmpty()) return 0.5f
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var count = 0
        for (sample in curve) {
            val e = sample.energy.toFloat()
            if (!e.isFinite()) continue
            if (e < min) min = e
            if (e > max) max = e
            sum += e
            count++
        }
        if (count == 0 || max <= min) return 0.5f
        val mean = (sum / count).toFloat()
        return ((mean - min) / (max - min)).coerceIn(0f, 1f)
    }
}
