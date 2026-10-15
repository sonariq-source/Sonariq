package com.music.sonariq.data.lyrics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KrcLyricsTest {

    private val sample = """
        [ar:Test Artist]
        [ti:Test Title]
        [al:Test Album]
        [by:kugou]
        [offset:0]
        [1000,3000]<0,500,0>hello<500,200,0> <700,900,0>world
        [5000,2500]<0,400,0>next<400,600,0>line<1000,500,0>here
    """.trimIndent()

    @Test
    fun `parses word timings from krc lines`() {
        val lines = KrcLyrics.parse(sample)
        assertEquals(2, lines.size)

        val first = lines[0]
        assertEquals(1000L, first.timeMs)
        assertEquals("hello world", first.text)
        assertEquals(2, first.words.size)
        assertEquals(1000L, first.words[0].startMs)
        assertEquals(1500L, first.words[0].endMs)
        assertEquals("hello", first.words[0].text)
        assertEquals(1700L, first.words[1].startMs)
        assertEquals(2600L, first.words[1].endMs)
        assertEquals("world", first.words[1].text)
        assertTrue(first.isWordSynced)
        assertFalse(first.wordsEstimated)

        val second = lines[1]
        assertEquals(5000L, second.timeMs)
        assertEquals("next line here", second.text)
        assertEquals(3, second.words.size)
    }

    @Test
    fun `decrypt round-trips an encrypted krc payload`() {
        val encrypted = encryptKrc(sample)
        val decrypted = KrcLyrics.decrypt(encrypted)
        assertEquals(sample, decrypted)
        // And the decrypted text parses to word-synced lines.
        assertTrue(KrcLyrics.parse(decrypted!!).any { it.isWordSynced })
    }

    @Test
    fun `decrypt accepts plain krc text`() {
        val base64 = Base64.getEncoder().encodeToString(sample.toByteArray(Charsets.UTF_8))
        assertEquals(sample, KrcLyrics.decrypt(base64))
    }

    @Test
    fun `decrypt rejects non-krc content`() {
        val base64 = Base64.getEncoder().encodeToString("just some text".toByteArray(Charsets.UTF_8))
        assertNull(KrcLyrics.decrypt(base64))
        assertNull(KrcLyrics.decrypt("not-base64!!!"))
    }

    @Test
    fun `parse ignores metadata and malformed lines`() {
        val lines = KrcLyrics.parse("[ar:artist]\n[ti:title]\nnot a krc line\n[1000,2000]no tokens here")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `word offsets are relative to the line start`() {
        // Starts under MIN_GAP_MS so withInstrumentalGaps adds no intro marker.
        val lines = KrcLyrics.parse("[1000,5000]<250,750,0>word")
        assertEquals(1, lines.size)
        assertEquals(1250L, lines[0].words[0].startMs)
        assertEquals(2000L, lines[0].words[0].endMs)
    }

    /** Builds a real encrypted KRC payload the way KuGou serves it. */
    private fun encryptKrc(plain: String): String {
        val deflater = Deflater()
        deflater.setInput(plain.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        val compressed = out.toByteArray()
        val key = byteArrayOf(0x40, 0x47, 0x65, 0x6B, 0x79)
        for (i in compressed.indices) {
            compressed[i] = (compressed[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val header = byteArrayOf(0x6B, 0x72, 0x63, 0x31)
        return Base64.getEncoder().encodeToString(header + compressed)
    }
}
