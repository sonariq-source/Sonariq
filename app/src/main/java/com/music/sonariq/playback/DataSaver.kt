package com.music.sonariq.playback

import com.music.sonariq.data.settings.AudioQuality

/**
 * Data saver: one master switch that caps streaming quality everywhere, plus a
 * Wi-Fi-only gate that stops streaming on metered connections entirely.
 *
 * This extends the existing per-connection ceilings
 * ([AppSettings.audioQualityWifi] / [AppSettings.audioQualityCellular]) rather
 * than duplicating them: when the saver is off, those two decide exactly as
 * before; when it is on, [dataSaverQuality] overrides both. The override point
 * is [AppSettings.effectiveAudioQuality], which every stream resolver already
 * reads — see the manifest for the one-line Phase 2 edit.
 *
 * The Wi-Fi-only toggle is the stronger half. Downloads already have
 * `wifiOnlyDownloads`; streaming had no equivalent, so a listener on a
 * metered plan who wanted zero mobile data had no way to ask for it. When it
 * is on and the connection is metered, only offline content (downloads and
 * local files) plays — a remote track errors with a data-saver message
 * instead of resolving a stream.
 */
object DataSaver {

    /**
     * The quality ceiling for a stream started right now.
     *
     * @param dataSaver whether the master switch is on.
     * @param saverQuality the cap that applies while it is on.
     * @param metered the current connection, or null when unknown.
     * @param wifiQuality the existing Wi-Fi ceiling.
     * @param cellularQuality the existing metered-connection ceiling.
     */
    fun effectiveQuality(
        dataSaver: Boolean,
        saverQuality: AudioQuality,
        metered: Boolean?,
        wifiQuality: AudioQuality,
        cellularQuality: AudioQuality,
    ): AudioQuality = when {
        dataSaver -> saverQuality
        metered == true -> cellularQuality
        else -> wifiQuality
    }

    /**
     * Whether a remote stream may be resolved on the connection in hand.
     *
     * A null [metered] means the connection state is unknown (or there is no
     * network at all) and is deliberately allowed through — the same call the
     * download gate makes: a stream with nothing to stream over fails on the
     * network and says so, which is true, where refusing it here would blame a
     * Wi-Fi setting for an outage.
     */
    fun streamingAllowed(wifiOnlyStreaming: Boolean, metered: Boolean?): Boolean =
        !wifiOnlyStreaming || metered != true
}
