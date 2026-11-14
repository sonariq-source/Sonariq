package com.music.sonariq.playback

/**
 * The listener's content country: where the popular charts should be
 * local to, resolved on the device and nowhere else.
 *
 * Three on-device signals, in order of how directly they describe the
 * listener's market: the country of the mobile network they are on,
 * the country of their SIM, and the country their device locale is set
 * to. The first that names a real ISO country wins. Nothing is sent
 * anywhere to learn this — the device already knows it — and when none
 * of the three knows either, the answer is null, which the charts read
 * as the global charts rather than an error.
 *
 * Only strict two-letter ISO codes count. Devices report empty strings
 * when a signal is absent, region numbers in some locales, and the odd
 * lowercase or padded value; all of that normalizes away or falls
 * through to the next signal.
 */
object ContentCountry {

    fun resolve(
        networkIso: String?,
        simIso: String?,
        localeCountry: String?,
    ): String? = sequenceOf(networkIso, simIso, localeCountry)
        .mapNotNull(::normalize)
        .firstOrNull()

    private fun normalize(raw: String?): String? {
        val iso = raw?.trim()?.uppercase().orEmpty()
        return iso.takeIf { it.length == 2 && it.all { it in 'A'..'Z' } }
    }
}
