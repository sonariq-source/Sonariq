package com.music.sonariq.data.lyrics

/**
 * A first guess at the language a set of lyric lines is sung in, from the
 * words themselves.
 *
 * A transcription of the track does measurably better when it is told
 * which language to listen for, and the lyric lines a lookup already has
 * usually give that away for free: the small function words of a language
 * — its articles, pronouns and particles — turn up in almost every line,
 * while the words that carry the song's subject barely repeat at all.
 * So this detector counts nothing else. Every normalised word of every
 * top-level line is checked against a Tagalog/Filipino stopword list and
 * an English one, and each list's hits are counted with repetition: a
 * word sung ten times is ten pieces of evidence, not one.
 *
 * Both sides go through the aligner's [normalizeToken] first — the
 * lyric words on the way in, and the stopword lists themselves when they
 * are built — so case, punctuation, apostrophes and held doubled letters
 * cannot split a match: a sung "will" normalises to "wil", and the list's
 * "will" has been normalised to the same "wil" waiting for it.
 *
 * The answer is deliberately reluctant. A language is returned only when
 * its list has at least [MIN_HITS] hits AND strictly more hits than the
 * other list: a tie, a near-tie, or a lyric too sparse in function words
 * to judge all come back null, and the caller transcribes without a
 * language hint rather than with a wrong one. Background lines are not
 * counted — an echoed half-phrase is not a fair sample of the song.
 */
object LyricLanguageDetector {

    /**
     * "tl" when these [lines] read as Tagalog/Filipino, "en" when they
     * read as English, and null when the evidence is too thin or too
     * even to name one — see the class doc for the exact bar.
     */
    fun detect(lines: List<LyricLine>): String? {
        var tagalogHits = 0
        var englishHits = 0
        for (line in lines) {
            if (line.text.isBlank()) continue
            for (unit in normalizeToken(line.text).split(' ')) {
                if (unit.isEmpty()) continue
                if (unit in TAGALOG_STOPWORDS) tagalogHits += 1
                if (unit in ENGLISH_STOPWORDS) englishHits += 1
            }
        }
        return when {
            tagalogHits >= MIN_HITS && tagalogHits > englishHits -> "tl"
            englishHits >= MIN_HITS && englishHits > tagalogHits -> "en"
            else -> null
        }
    }

    /** The fewest stopword hits a language needs before it can be named at all. */
    private const val MIN_HITS = 3

    /**
     * The stopword lists, each entry already run through [normalizeToken]
     * — see the class doc for why the lists are normalised too.
     */
    private val TAGALOG_STOPWORDS: Set<String> = setOf(
        "ang", "ng", "sa", "na", "ay", "ko", "mo", "ni", "si", "ka",
        "ako", "ikaw", "siya", "mga", "ito", "iyan", "iyon", "hindi",
        "rin", "din", "lang", "pa", "ba", "po", "nang", "kung", "pero",
        "kasi", "daw", "raw", "yung", "niya", "nila", "kami", "tayo",
        "sila", "akin", "iyo",
    ).map { normalizeToken(it) }.filter { it.isNotEmpty() }.toSet()

    private val ENGLISH_STOPWORDS: Set<String> = setOf(
        "the", "and", "you", "i", "to", "of", "a", "in", "that", "it",
        "is", "was", "for", "on", "are", "as", "with", "his", "they",
        "at", "be", "this", "have", "from", "or", "one", "had", "by",
        "words", "but", "not", "what", "all", "were", "we", "when",
        "your", "can", "said", "there", "use", "an", "each", "which",
        "she", "do", "how", "their", "will", "other", "about", "out",
        "many", "then", "them", "these", "so", "some", "her", "would",
        "make", "like", "him", "into", "time", "has", "look", "two",
        "more", "go", "no", "way", "could", "my", "than", "first",
        "been", "who", "its", "now",
    ).map { normalizeToken(it) }.filter { it.isNotEmpty() }.toSet()
}
