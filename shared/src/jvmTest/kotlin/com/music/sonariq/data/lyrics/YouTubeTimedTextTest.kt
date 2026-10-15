package com.music.sonariq.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeTimedTextTest {

    private val srv3 = """
        <?xml version="1.0" encoding="utf-8" ?>
        <timedtext format="3">
        <head><pen id="1" fc="#E5E5E5"/></head>
        <body>
        <p t="0" d="3000" w="1">
        <s t="0" d="500">hello</s><s t="500" d="200"> </s><s t="700" d="900">world</s>
        </p>
        <p t="4000" d="2500" w="1">
        <s t="4000" d="600">next</s><s t="4600" d="400"> </s><s t="5000" d="800">line</s>
        </p>
        </body>
        </timedtext>
    """.trimIndent()

    @Test
    fun `parses word timings from srv3`() {
        val lines = YouTubeTimedText.parseSrv3(srv3)
        assertEquals(2, lines.size)

        val first = lines[0]
        assertEquals(0L, first.timeMs)
        assertEquals("hello world", first.text)
        assertEquals(2, first.words.size)
        assertEquals(0L, first.words[0].startMs)
        assertEquals(500L, first.words[0].endMs)
        assertEquals("hello", first.words[0].text)
        assertEquals(700L, first.words[1].startMs)
        assertEquals(1600L, first.words[1].endMs)
        assertEquals("world", first.words[1].text)
        assertTrue(first.isWordSynced)
        assertFalse(first.wordsEstimated)
    }

    @Test
    fun `line without word spans parses to nothing`() {
        val xml = """<timedtext><body><p t="0" d="3000">plain line</p></body></timedtext>"""
        assertTrue(YouTubeTimedText.parseSrv3(xml).isEmpty())
    }

    @Test
    fun `garbage input parses to nothing`() {
        assertTrue(YouTubeTimedText.parseSrv3("not xml at all").isEmpty())
        assertTrue(YouTubeTimedText.parseSrv3("").isEmpty())
    }

    @Test
    fun `second line keeps its own timings`() {
        val lines = YouTubeTimedText.parseSrv3(srv3)
        val second = lines[1]
        assertEquals(4000L, second.timeMs)
        assertEquals("next line", second.text)
        assertEquals(4000L, second.words[0].startMs)
        assertEquals(5000L, second.words[1].startMs)
    }
}
