package com.music.sonariq.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The content-country resolution: the network's country is the most
 * direct signal, the SIM's is next, the locale is the last resort, and
 * anything that is not a clean two-letter ISO code falls through
 * rather than steering the charts somewhere wrong.
 */
class ContentCountryTest {

    @Test
    fun networkCountryWins() {
        assertEquals(
            "PH",
            ContentCountry.resolve(networkIso = "PH", simIso = "US", localeCountry = "GB"),
        )
    }

    @Test
    fun simCountryIsTheFallbackWhenTheNetworkNamesNone() {
        assertEquals(
            "PH",
            ContentCountry.resolve(networkIso = "", simIso = "ph", localeCountry = "US"),
        )
        assertEquals(
            "PH",
            ContentCountry.resolve(networkIso = null, simIso = "PH", localeCountry = "US"),
        )
    }

    @Test
    fun localeCountryIsTheLastResort() {
        assertEquals(
            "PH",
            ContentCountry.resolve(networkIso = null, simIso = null, localeCountry = "PH"),
        )
    }

    @Test
    fun malformedSignalsFallThrough() {
        // Region numbers, three-letter codes and padded noise are not
        // countries; each falls through to the next signal.
        assertEquals(
            "PH",
            ContentCountry.resolve(networkIso = "419", simIso = " usa ", localeCountry = "ph"),
        )
        assertEquals(
            "JP",
            ContentCountry.resolve(networkIso = "P1", simIso = null, localeCountry = "JP"),
        )
    }

    @Test
    fun noSignalMeansGlobal() {
        assertNull(ContentCountry.resolve(null, null, null))
        assertNull(ContentCountry.resolve("", "  ", ""))
        assertNull(ContentCountry.resolve("123", null, "toolong"))
    }
}
