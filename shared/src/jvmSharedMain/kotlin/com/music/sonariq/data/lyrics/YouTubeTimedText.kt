package com.music.sonariq.data.lyrics

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * YouTube's timedtext caption format with word timings (`fmt=srv3`).
 *
 * The caption track URLs from a player response serve XML where each
 * paragraph holds one `<s>` per word (and per inter-word space) with its own
 * absolute `t` (start, ms) and `d` (duration, ms):
 *
 * ```xml
 * <timedtext format="3"><body>
 *   <p t="0" d="3000" w="1">
 *     <s t="0" d="500">hello</s><s t="500" d="200"> </s><s t="700" d="900">world</s>
 *   </p>
 * </body></timedtext>
 * ```
 *
 * Those are genuine measured timings — the same ones YouTube's own player
 * highlights — so lines parsed here are word-synced, not estimates. A track
 * served without word elements (plain `<text start dur>`) parses to nothing
 * here, and the caller falls back to the line-level transcript JSON.
 */
object YouTubeTimedText {

    /**
     * srv3 XML as word-timed lyric lines, or an empty list when the document
     * has no word-level timings to give.
     */
    fun parseSrv3(xml: String): List<LyricLine> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val paragraphs = document.getElementsByTagName("p")
        val lines = ArrayList<LyricLine>(paragraphs.length)
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val lineStart = paragraph.getAttribute("t").toLongOrNull() ?: continue
            val spans = paragraph.getElementsByTagName("s")
            if (spans.length == 0) continue
            val runs = ArrayList<TimedRun>(spans.length)
            for (j in 0 until spans.length) {
                val span = spans.item(j) as? Element ?: continue
                val start = span.getAttribute("t").toLongOrNull() ?: continue
                val duration = span.getAttribute("d").toLongOrNull() ?: continue
                val text = span.textContent.orEmpty()
                if (text.isEmpty()) continue
                runs += TimedRun(start, start + duration, text)
            }
            val words = wordsFromRuns(runs, spacingIsExplicit = true)
                .filter { it.text.isNotBlank() }
            if (words.isEmpty()) continue
            val text = words.joinToString(" ") { it.text }.trim()
            if (text.isEmpty()) continue
            lines += LyricLine(
                timeMs = minOf(lineStart, words.first().startMs),
                text = text,
                words = words,
            )
        }
        lines.sortedBy { it.timeMs }.withInstrumentalGaps()
    }.getOrDefault(emptyList())
}
