package com.music.sonariq.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update checker's version comparison, across the change from two-part
 * names ("2.30") to the three-part scheme ("2.3.0", then 2.3.1 ... 2.3.9,
 * 2.4.0, up to 2.9.9 -> 3.0.0). A legacy two-part name with a two-digit
 * second part unfolds by splitting its digits: "2.30" is 2.3.0, "2.29" is
 * 2.2.9, "2.28" is 2.2.8. Without that, [2, 30] reads as newer than
 * [2, 3, 1] and installs on 2.30 are never offered an update again.
 */
class AppUpdateCheckerVersionTest {

    @Test
    fun legacyTwoPartInstallIsOfferedThreePartUpdate() {
        assertTrue(AppUpdateChecker.isNewer("2.3.1", "2.30"))
        assertTrue(AppUpdateChecker.isNewer("2.3.0", "2.29"))
    }

    @Test
    fun threePartInstallIsOfferedNextPatch() {
        assertTrue(AppUpdateChecker.isNewer("2.3.2", "2.3.1"))
    }

    @Test
    fun patchRollsOverIntoMinor() {
        assertTrue(AppUpdateChecker.isNewer("2.4.0", "2.3.9"))
    }

    @Test
    fun minorRollsOverIntoMajor() {
        assertTrue(AppUpdateChecker.isNewer("3.0.0", "2.9.9"))
    }

    @Test
    fun equalVersionsAreNotAnUpdate() {
        assertFalse(AppUpdateChecker.isNewer("2.3.1", "2.3.1"))
        // The same version written in the legacy style is still equal.
        assertFalse(AppUpdateChecker.isNewer("2.3.0", "2.30"))
        assertFalse(AppUpdateChecker.isNewer("2.30", "2.3.0"))
    }

    @Test
    fun lowerOfferIsNotAnUpdate() {
        assertFalse(AppUpdateChecker.isNewer("2.3.0", "2.3.1"))
        assertFalse(AppUpdateChecker.isNewer("2.2.9", "2.30"))
        assertFalse(AppUpdateChecker.isNewer("2.30", "2.3.1"))
    }
}
