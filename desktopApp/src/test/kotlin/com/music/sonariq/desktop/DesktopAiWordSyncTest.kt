package com.music.sonariq.desktop

import com.music.sonariq.data.lyrics.AiLyricAligner
import com.music.sonariq.data.lyrics.LyricLine
import com.music.sonariq.data.lyrics.LyricWord
import com.music.sonariq.data.lyrics.TranscriptionClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop AI word-sync gate, ladder and candidate check — the phone's semantics, on the desktop's copies. */
class DesktopAiWordSyncTest {

    @Test
    fun theGateNeedsTheSwitchTheKeyAndNoJobRunning() {
        assertTrue(desktopAiWordSyncGateAllows(enabled = true, keyBlank = false, alreadyInFlight = false))
        assertFalse(desktopAiWordSyncGateAllows(enabled = false, keyBlank = false, alreadyInFlight = false))
        assertFalse(desktopAiWordSyncGateAllows(enabled = true, keyBlank = true, alreadyInFlight = false))
        assertFalse(desktopAiWordSyncGateAllows(enabled = true, keyBlank = false, alreadyInFlight = true))
    }

    @Test
    fun theLadderDropsItsSteersInOrder() {
        val plan = desktopAiAttemptPlan("en")
        assertEquals(4, plan.size)
        assertEquals(TranscriptionClient.DEFAULT_MODEL, plan[0].model)
        assertEquals("en", plan[0].language)
        assertTrue(plan[0].withPrompt)
        assertNull(plan[1].language)
        assertEquals(TranscriptionClient.ALTERNATE_MODEL, plan[2].model)
        assertEquals("en", plan[2].language)
        assertFalse(plan[3].withPrompt)
        assertNull(plan[3].language)
    }

    @Test
    fun withoutALanguageTheLadderCollapsesItsDuplicateRungs() {
        assertEquals(3, desktopAiAttemptPlan(null).size)
    }

    @Test
    fun onlyTheFirstCompleteAttemptEarnsStoring() {
        val incomplete = AiLyricAligner.AlignOutcome(
            lines = null, passedLeadLines = 1, leadLineCount = 3,
            calibrationDiscarded = false, offsetMs = 0L,
        )
        val complete = AiLyricAligner.AlignOutcome(
            lines = emptyList(), passedLeadLines = 3, leadLineCount = 3,
            calibrationDiscarded = false, offsetMs = 0L,
        )
        assertEquals(complete, pickDesktopAiAttempt(listOf(null, incomplete, complete)))
        assertNull(pickDesktopAiAttempt(listOf(null, incomplete)))
        assertNull(pickDesktopAiAttempt(emptyList()))
    }

    @Test
    fun lineSyncedLyricsAreCandidatesAndWordSyncedOnesAreNot() {
        val line = LyricLine(timeMs = 0L, text = "hello world")
        assertTrue(listOf(line).isDesktopAiWordSyncCandidate())

        val wordSynced = line.copy(
            words = listOf(
                LyricWord(startMs = 0L, endMs = 500L, text = "hello"),
                LyricWord(startMs = 500L, endMs = 1_000L, text = "world"),
            ),
        )
        assertFalse(listOf(wordSynced).isDesktopAiWordSyncCandidate())
        assertFalse(emptyList<LyricLine>().isDesktopAiWordSyncCandidate())
    }

    @Test
    fun theDurationTestDemandsAMatchWhenTheTrackLengthIsKnown() {
        assertTrue(DesktopAiWordSync.durationAcceptable(probedMs = 61_500L, referenceMs = 60_000L, streamResolved = true))
        assertFalse(DesktopAiWordSync.durationAcceptable(probedMs = 65_000L, referenceMs = 60_000L, streamResolved = true))
        assertFalse(DesktopAiWordSync.durationAcceptable(probedMs = null, referenceMs = 60_000L, streamResolved = false))
    }

    @Test
    fun theDurationTestTrustsAFileOnlyWhenNoStreamExistsAndNoLengthIsKnown() {
        assertTrue(DesktopAiWordSync.durationAcceptable(probedMs = null, referenceMs = 0L, streamResolved = false))
        assertFalse(DesktopAiWordSync.durationAcceptable(probedMs = 60_000L, referenceMs = 0L, streamResolved = true))
    }
}
