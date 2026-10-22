package com.music.sonariq

import com.music.sonariq.data.lyrics.LyricTiming
import com.music.sonariq.download.downloadNeedsAiOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloads-scan predicate in `LyricsWarmupWorker.kt` — see
 * [downloadNeedsAiOffer].
 *
 * A downloaded track is offered for AI word sync only where neither the
 * file's own tag snapshot nor the repository cache already holds word
 * timings for it. That is a pure decision over two timings, which is why
 * its whole truth table can be pinned down here without a device: each
 * test below is one row of it.
 */
class LyricsWarmupDownloadsTest {

    @Test
    fun `line-synced embedded lyrics with no cache need an offer`() {
        assertTrue(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.LINE_SYNCED,
                cachedTiming = null,
            ),
        )
    }

    @Test
    fun `line-synced embedded lyrics with a line-synced cache need an offer`() {
        assertTrue(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.LINE_SYNCED,
                cachedTiming = LyricTiming.LINE_SYNCED,
            ),
        )
    }

    @Test
    fun `word-synced embedded lyrics need no offer`() {
        assertFalse(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.WORD_SYNCED,
                cachedTiming = null,
            ),
        )
    }

    @Test
    fun `AI-measured embedded lyrics need no offer`() {
        assertFalse(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.WORD_SYNCED_AI_MEASURED,
                cachedTiming = null,
            ),
        )
    }

    @Test
    fun `line-synced embedded lyrics with a word-synced cache need no offer`() {
        assertFalse(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.LINE_SYNCED,
                cachedTiming = LyricTiming.WORD_SYNCED,
            ),
        )
    }

    @Test
    fun `line-synced embedded lyrics with an AI-measured cache need no offer`() {
        assertFalse(
            downloadNeedsAiOffer(
                embeddedTiming = LyricTiming.LINE_SYNCED,
                cachedTiming = LyricTiming.WORD_SYNCED_AI_MEASURED,
            ),
        )
    }

    @Test
    fun `missing embedded lyrics need no offer`() {
        assertFalse(
            downloadNeedsAiOffer(
                embeddedTiming = null,
                cachedTiming = null,
            ),
        )
    }
}
