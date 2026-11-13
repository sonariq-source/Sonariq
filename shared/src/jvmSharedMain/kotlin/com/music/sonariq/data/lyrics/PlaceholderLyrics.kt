package com.music.sonariq.data.lyrics

/**
 * Lyrics that are not lyrics: the placeholder a source serves for a track
 * it has no words for, dressed up as a result.
 *
 * The notorious shape is the instrumental card the Chinese catalogues
 * return — one stamped line whose whole text says, in effect, "pure
 * music, please enjoy" — but every catalogue has its own version of the
 * same shrug: "no lyrics found" served as content, an "[Instrumental]"
 * card standing where a song's words should be, a timed file with no
 * text in it at all. Served as a result, a placeholder does double
 * damage: the panel shows a sentence that is not the song, and the
 * lookup believes it found something, so the hunt for real lyrics — and
 * the AI transcription that stands in for them — never starts.
 *
 * Detection is deliberately narrow, and the narrowness is the point.
 * A result is a placeholder only when it carries **no sung text at
 * all**: every non-blank line it has is a marker (see [isMarkerLine]),
 * or it has no non-blank line to begin with. A real lyric sheet that
 * merely *contains* an instrumental marker — a break written into the
 * middle of the song, a line that happens to use the word — is a song
 * with an instrumental stretch in it, and passes untouched: the rule
 * condemns a result that is *nothing but* the marker, never a result
 * that mentions one. Sung text in any line, anywhere in the result,
 * settles the question the other way.
 *
 * Pure: no I/O, no clock. Used at the repository's ingestion funnel, on
 * its cache reads, and by [LyricsValidation], so a placeholder is
 * refused the same way at every door it could walk through.
 */
object PlaceholderLyrics {

    /**
     * Whether [lines], taken as a whole result, are a placeholder rather
     * than lyrics: no line in them carries sung text that is not itself
     * a marker. Blank lines — the timed gaps a synced file uses for its
     * instrumental stretches — are not evidence either way and are
     * skipped; a result of nothing but gaps and markers, or of nothing
     * at all, is a placeholder.
     */
    fun isPlaceholderOnly(lines: List<LyricLine>): Boolean {
        val texts = lines.map { it.text }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return true
        return texts.all { isMarkerLine(it) }
    }

    /**
     * Whether one line's [text] is itself a placeholder marker — the
     * whole line says "there are no words here", in one of the shapes
     * the catalogues serve it in.
     *
     * The comparison is made on a normalised form — lowercased, all
     * whitespace removed, surrounding brackets and sentence punctuation
     * stripped — so the same marker is recognised however a source
     * dresses it: bracketed, punctuated, spaced or not. The tests then
     * are, in order:
     *
     *  - The Chinese instrumental cards: any short line carrying
     *    "纯音乐" ("pure music") is the card — the canonical serving is
     *    "纯音乐，请欣赏", and its variants differ only in politeness.
     *    The length cap is what keeps this a marker test rather than a
     *    word test: a sung line that happens to use the phrase inside a
     *    real sentence is longer than any card, and the whole-result
     *    rule in [isPlaceholderOnly] protects it a second time besides.
     *  - "暂无歌词" ("no lyrics yet") wherever it sits in the line: the
     *    other Chinese catalogues' shrug, served with and without
     *    trimmings.
     *  - The English cards: "instrumental" (bracketed or not, and short
     *    dressings of it), and the "no lyrics …" / "lyrics not …"
     *    sentences with their spacing removed, so "No lyrics found"
     *    and "no-lyrics" land in the same place.
     *  - A line of nothing but music-note symbols, the timed files'
     *    other way of writing an instrumental break.
     */
    fun isMarkerLine(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (normalized.all { it in NOTE_SYMBOLS }) return true
        if (normalized.contains("纯音乐") && normalized.length <= PURE_MUSIC_MAX_CHARS) {
            return true
        }
        if (normalized.contains("暂无歌词")) return true
        if (normalized == "instrumental") return true
        if (normalized.contains("instrumental") &&
            normalized.length <= INSTRUMENTAL_MAX_CHARS
        ) {
            return true
        }
        if (normalized.contains("nolyrics") || normalized.contains("lyricsnot") ||
            normalized.contains("lyricsarenot")
        ) {
            return true
        }
        return false
    }

    /**
     * [text] reduced to the form markers are compared in: lowercased,
     * whitespace removed outright (the Chinese cards are written both
     * with and without the comma, and spacing is never the difference
     * between a marker and a lyric), and the brackets and sentence
     * punctuation sources wrap markers in stripped from both ends.
     */
    private fun normalize(text: String): String {
        val lowered = text.lowercase().filterNot { it.isWhitespace() }
        return lowered.trim { it in WRAPPING }
    }

    /** The longest a line carrying "纯音乐" can be and still be the card, not a sentence. */
    private const val PURE_MUSIC_MAX_CHARS = 16

    /** The longest a line carrying "instrumental" can be and still be a card, not a sentence. */
    private const val INSTRUMENTAL_MAX_CHARS = 24

    /** Brackets and sentence punctuation a marker may be wrapped in. */
    private val WRAPPING = setOf(
        '[', ']', '(', ')', '（', '）', '【', '】', '〔', '〕',
        '.', '。', ',', '，', '、', '!', '！', '?', '？',
        ':', '：', ';', '；', '-', '—', '~', '～', '·', '…',
        '"', '\'', '“', '”', '‘', '’', '<', '>', '《', '》',
    )

    /** The symbols a timed file writes for a wordless stretch. */
    private val NOTE_SYMBOLS = setOf('♪', '♫', '♩', '♬', '🎵', '🎶')
}
