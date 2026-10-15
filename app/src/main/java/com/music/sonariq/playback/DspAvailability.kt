package com.music.sonariq.playback

/**
 * Whether a DSP effect — the equaliser, its bass/treble trims, spatial audio —
 * can actually touch the playing track's samples.
 *
 * The app's effects are all in-house DSP inside ExoPlayer's pipeline (see
 * [EqualizerProcessor]); no platform `android.media.audiofx` effect is wired
 * anywhere in the audio path. A platform `BassBoost` was considered and
 * deliberately not used: it hangs off the audio session downstream of the app,
 * so it would stack on top of the in-house equaliser and double-process the
 * same bands, and it is equally dead on the one path where nothing can run —
 * a passthrough/offload bitstream is never decoded, so there are no samples
 * for any effect, platform or otherwise, to filter. Bass and treble are tone
 * trims into the DSP's own shelf sections instead, which work everywhere the
 * DSP runs.
 *
 * The one genuine unavailability is therefore the pipeline's, and it is
 * measured, not guessed: the sink publishes it from the negotiated path via
 * [AudioOutputStatus.publishDsp], and [dspEffectAvailability] is the pure
 * reading of that measurement the UI keys its "not available" indicators off.
 */
enum class DspEffectAvailability {
    /** The DSP chain runs on this output; effects work. */
    AVAILABLE,

    /** Passthrough/offload bitstream: never decoded, nothing to filter. */
    UNAVAILABLE_BITSTREAM,
}

/**
 * Pure reading of [AudioOutputStatus.Snapshot.dspAvailable]. Kept as a
 * function rather than read inline so the mapping from snapshot to indicator
 * is unit-testable without Android.
 */
fun dspEffectAvailability(snapshot: AudioOutputStatus.Snapshot): DspEffectAvailability =
    if (snapshot.dspAvailable) DspEffectAvailability.AVAILABLE else DspEffectAvailability.UNAVAILABLE_BITSTREAM
