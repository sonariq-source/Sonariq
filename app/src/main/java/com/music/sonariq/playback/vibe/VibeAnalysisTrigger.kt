package com.music.sonariq.playback.vibe

import android.content.Context
import android.util.Log
import com.music.sonariq.data.model.Song
import com.music.sonariq.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Kicks off background vibe computation for a track the first time it plays.
 *
 * Runs entirely off the playback path: a low-priority coroutine that
 *  1. skips tracks that already have a confident vibe stored,
 *  2. fetches Last.fm tags (network, best-effort),
 *  3. reads the existing [TrackAnalysis] if the DJ analyzer already ran,
 *  4. folds in user behavior (currently a neutral signal; the feedback loop
 *     in [VibeStore.recordVibeFeedback] carries the learned part),
 *  5. stores the combined vector + confidence in [VibeStore].
 *
 * Nothing here touches audio directly and nothing is uploaded anywhere.
 * The heavy DSP (BPM/key/energy) is the existing TrackAnalyzer's job;
 * this trigger only reads its already-computed [TrackAnalysis].
 *
 * Wire-in: call [onTrackStarted] from PlaybackService.onTrackBecameCurrent.
 */
object VibeAnalysisTrigger {

    private const val TAG = "VibeTrigger"

    /** Below this confidence a stored vibe is recomputed when the track plays again. */
    private const val RECOMPUTE_BELOW_CONFIDENCE = 0.35f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Best-effort entry point. Returns immediately; all work happens
     * on a background dispatcher. Safe to call on every track change.
     *
     * @param trackAnalysisProvider returns the existing TrackAnalysis for a
     *   videoId, or null if the DJ analyzer never ran for it. The trigger
     *   never starts analysis itself — that stays the DJ pipeline's job.
     */
    fun onTrackStarted(
        context: Context,
        song: Song,
        trackAnalysisProvider: (String) -> TrackAnalysis? = { null },
    ) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val existing = VibeStore.getVibe(appContext, song.videoId)
                if (existing != null && existing.confidence >= RECOMPUTE_BELOW_CONFIDENCE) {
                    return@launch
                }

                // Signal 1: Last.fm tags (network, never throws).
                val tags = runCatching {
                    LastFmTags.tagsToVibe(LastFmTags.getTopTags(song.artist, song.title))
                }.getOrDefault(VibeVector())

                // Signal 2: existing audio analysis, if the DJ pipeline ran.
                val analysis = runCatching { trackAnalysisProvider(song.videoId) }.getOrNull()

                // Signal 3: behavior — start neutral. The feedback loop
                // (skips/likes/"not this vibe") shapes this over time via
                // VibeStore.recordVibeFeedback.
                val behavior = VibeVector()

                val (vector, confidence) = VibeAnalyzer.analyze(tags, analysis, behavior)
                if (vector.isEmpty()) {
                    Log.d(TAG, "no vibe signal for ${song.videoId}")
                    return@launch
                }
                VibeStore.saveVibe(appContext, song.videoId, vector, confidence)
                Log.d(TAG, "vibe stored for ${song.videoId}: ${vector.dominant()} (conf=$confidence)")
            }.onFailure {
                Log.w(TAG, "vibe analysis failed for ${song.videoId}", it)
            }
        }
    }
}
