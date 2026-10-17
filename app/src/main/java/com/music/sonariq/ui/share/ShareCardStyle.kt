package com.music.sonariq.ui.share

/**
 * The look of a shared lyrics story card.
 *
 * Classic is the original Sonariq card: blurred sleeve backdrop, the lyric
 * quoted big in the middle, sleeve thumb and title underneath. Wide spreads
 * the lyric larger across the frame. Minimal strips it to the words on a
 * clean dark field with the Sonariq waveform mark.
 */
enum class ShareCardStyle {
    Classic,
    Wide,
    Minimal,
}
