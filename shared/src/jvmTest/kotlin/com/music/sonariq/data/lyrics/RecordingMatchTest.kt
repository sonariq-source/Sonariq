package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RecordingMatch]: whether a provider's candidate can be the recording
 * being played, judged from the names alone. These are the spec's
 * identity scenarios at the level where they are decided — same title,
 * different artist; original against remix; live against studio — plus
 * the false-positive discipline: words that are part of a song's actual
 * name declare nothing.
 */
class RecordingMatchTest {

    @Test
    fun bracketedVersionsAreDeclared() {
        assertEquals(
            setOf(RecordingMatch.Version.REMIX),
            RecordingMatch.versionMarkers("Song (Remix)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.LIVE),
            RecordingMatch.versionMarkers("Song (Live)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.ACOUSTIC),
            RecordingMatch.versionMarkers("Song [Acoustic]"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.SPED_UP),
            RecordingMatch.versionMarkers("Song (Sped Up)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.SLOWED),
            RecordingMatch.versionMarkers("Song (Slowed + Reverb)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.RADIO_EDIT),
            RecordingMatch.versionMarkers("Song (Radio Edit)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.REMASTERED),
            RecordingMatch.versionMarkers("Song (Remastered 2011)"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.LIVE),
            RecordingMatch.versionMarkers("Song - Live at the Arena"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.REMIX),
            RecordingMatch.versionMarkers("Song (DJ Remix) [Club Edit]"),
        )
    }

    @Test
    fun trailingVersionWordsAreDeclared() {
        assertEquals(
            setOf(RecordingMatch.Version.REMIX),
            RecordingMatch.versionMarkers("Song Remix"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.LIVE),
            RecordingMatch.versionMarkers("Song Live"),
        )
        assertEquals(
            setOf(RecordingMatch.Version.ACOUSTIC),
            RecordingMatch.versionMarkers("Song Acoustic"),
        )
    }

    @Test
    fun titleWordsDeclareNothing() {
        // "Live" inside the name is the name: no brackets, no dash tail,
        // not the final word — nothing is declared.
        assertTrue(RecordingMatch.versionMarkers("Live Forever").isEmpty())
        assertTrue(RecordingMatch.versionMarkers("Olive Sessions").isEmpty())
        assertTrue(RecordingMatch.versionMarkers("Delivered in Person").isEmpty())
        assertTrue(RecordingMatch.versionMarkers("Plain Song Title").isEmpty())
    }

    @Test
    fun originalAndRemixAreIncompatible() {
        assertFalse(RecordingMatch.versionsCompatible("Song", "Song (Remix)"))
        assertFalse(RecordingMatch.versionsCompatible("Song (Remix)", "Song"))
        assertTrue(RecordingMatch.versionsCompatible("Song (Remix)", "Song (Club Remix)"))
    }

    @Test
    fun liveAndStudioAreIncompatible() {
        assertFalse(RecordingMatch.versionsCompatible("Song", "Song (Live)"))
        assertFalse(RecordingMatch.versionsCompatible("Song (Live)", "Song (Acoustic)"))
        assertTrue(RecordingMatch.versionsCompatible("Song (Live)", "Song (Live)"))
    }

    @Test
    fun spedUpAndSlowedAreIncompatibleWithEverythingButThemselves() {
        assertFalse(RecordingMatch.versionsCompatible("Song (Sped Up)", "Song"))
        assertFalse(RecordingMatch.versionsCompatible("Song (Sped Up)", "Song (Slowed)"))
        assertTrue(RecordingMatch.versionsCompatible("Song (Sped Up)", "Song (Sped Up)"))
    }

    @Test
    fun remasterDifferencesAreTolerated() {
        // Catalogues file remasters under the plain title as often as
        // not, and the performance is the same one: not a hard mismatch.
        assertTrue(RecordingMatch.versionsCompatible("Song (Remastered 2011)", "Song"))
        assertTrue(RecordingMatch.versionsCompatible("Song", "Song (Remastered)"))
    }

    @Test
    fun namesMatchOnEqualityAndContainment() {
        assertTrue(RecordingMatch.namesMatch("Dirty Ice Cream", "DIRTY ICE CREAM"))
        assertTrue(RecordingMatch.namesMatch("Song (Remix)", "Song (Remix)"))
        assertTrue(RecordingMatch.namesMatch("Song", "Song (Remix)"))
        assertTrue(RecordingMatch.namesMatch("Song!", "song"))
        assertFalse(RecordingMatch.namesMatch("Dirty Ice Cream", "Chocolate Cake"))
        assertFalse(RecordingMatch.namesMatch("Hello", "Goodbye"))
        assertFalse(RecordingMatch.namesMatch("", "Song"))
    }

    @Test
    fun artistsMatchAcrossCreditShapes() {
        assertTrue(RecordingMatch.artistMatches("CHOLOW", "cholow"))
        assertTrue(RecordingMatch.artistMatches("A feat. B", "A"))
        assertTrue(RecordingMatch.artistMatches("A", "A feat. B"))
        assertTrue(RecordingMatch.artistMatches("A & B", "A"))
        assertFalse(RecordingMatch.artistMatches("Artist One", "Artist Two"))
        assertFalse(RecordingMatch.artistMatches("", "A"))
    }

    @Test
    fun sameTitleDifferentArtistIsRejected() {
        assertFalse(
            RecordingMatch.identityAccepted(
                trackTitle = "Blue Sky",
                trackArtist = "First Singer",
                candidateTitle = "Blue Sky",
                candidateArtist = "Second Singer",
            ),
        )
    }

    @Test
    fun differentRecordingsOfTheSameSongAreRejected() {
        assertFalse(
            RecordingMatch.identityAccepted("Song", "Singer", "Song (Remix)", "Singer"),
        )
        assertFalse(
            RecordingMatch.identityAccepted("Song", "Singer", "Song (Live)", "Singer"),
        )
        assertFalse(
            RecordingMatch.identityAccepted("Song (Live)", "Singer", "Song", "Singer"),
        )
    }

    @Test
    fun theSameRecordingIsAccepted() {
        assertTrue(
            RecordingMatch.identityAccepted(
                "Dirty Ice Cream",
                "CHOLOW",
                "DIRTY ICE CREAM",
                "Cholow",
            ),
        )
        assertTrue(
            RecordingMatch.identityAccepted(
                "Song (Remix)",
                "Singer feat. Guest",
                "Song (Remix)",
                "Singer",
            ),
        )
    }
}
