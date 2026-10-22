package com.music.sonariq.data.lyrics

/**
 * The prompt budget, in characters, for [buildTranscriptionPrompt].
 *
 * The transcription endpoint weighs the prompt as context alongside the
 * audio, and a whole song's sheet would crowd out the audio it is meant
 * to steer: the longer the prompt, the more of the service's attention
 * goes to reading text and the less to listening. Roughly the first
 * thousand characters still cover the opening and first chorus of most
 * songs, which is where misheard words do the most damage to an
 * alignment — an early line lined up against the wrong words throws
 * every line after it — so the budget stops there and later verses are
 * left for the audio to carry on its own.
 */
const val TRANSCRIPTION_PROMPT_MAX_CHARS = 1024

/**
 * The prompt to hand the transcription endpoint for a track whose lyric
 * lines are [lines], or null when there is no text worth sending.
 *
 * The prompt is the track's own lyric text, in order: each line's lead
 * [LyricLine.text], blank lines skipped, joined with newlines, first
 * lines first. Only the lead text is taken — a line's background vocal
 * is an answering fragment, not the words the lead is singing, and
 * folding it in would steer the transcription toward words that are not
 * the line.
 *
 * Whole lines only, while the total stays within
 * [TRANSCRIPTION_PROMPT_MAX_CHARS]: a line cut in half mid-word is worse
 * context than the line simply not being there, so a line that would
 * cross the budget is dropped along with everything after it. The one
 * exception is a first non-blank line that alone exceeds the budget —
 * with nothing else to send, that line is hard-truncated to the budget
 * rather than sending no prompt at all.
 *
 * Pure function, no I/O: the same lines always build the same prompt.
 */
fun buildTranscriptionPrompt(lines: List<LyricLine>): String? {
    val texts = lines.map { it.text }.filter { it.isNotBlank() }
    if (texts.isEmpty()) return null
    val first = texts.first()
    if (first.length > TRANSCRIPTION_PROMPT_MAX_CHARS) {
        return first.substring(0, TRANSCRIPTION_PROMPT_MAX_CHARS)
    }
    val prompt = StringBuilder(first)
    for (text in texts.drop(1)) {
        // One more character for the newline joining this line on.
        if (prompt.length + 1 + text.length > TRANSCRIPTION_PROMPT_MAX_CHARS) break
        prompt.append('\n').append(text)
    }
    return prompt.toString()
}
