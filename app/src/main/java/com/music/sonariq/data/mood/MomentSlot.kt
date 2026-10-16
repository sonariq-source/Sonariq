package com.music.sonariq.data.mood

import com.music.sonariq.R
import java.time.LocalTime

/**
 * The five slices of the day the Home screen's "For this moment" section
 * understands, in the device's local time. [NIGHT] straddles midnight.
 *
 * @param labelRes the user-facing name, read through resources at render time.
 * @param greetingRes the time-of-day greeting shown above the name.
 * @param fallbackQuery the keyword search used when the listener's own history
 *   has too few tracks to seed from — still real results from YouTube Music,
 *   never anything invented.
 */
enum class MomentSlot(
    val labelRes: Int,
    val greetingRes: Int,
    val fallbackQuery: String,
) {
    MORNING(R.string.moment_morning, R.string.greeting_morning, "morning energy upbeat"),
    MIDDAY(R.string.moment_midday, R.string.greeting_afternoon, "midday focus work music"),
    AFTERNOON(R.string.moment_afternoon, R.string.greeting_afternoon, "afternoon drive hits"),
    EVENING(R.string.moment_evening, R.string.greeting_evening, "evening chill unwind"),
    NIGHT(R.string.moment_night, R.string.greeting_night, "late night vibes"),
    ;

    fun contains(hour: Int): Boolean = when (this) {
        // The one slot that wraps past midnight.
        NIGHT -> hour == 23 || hour in 0..4
        MORNING -> hour in 5..10
        MIDDAY -> hour in 11..14
        AFTERNOON -> hour in 15..18
        EVENING -> hour in 19..22
    }

    companion object {
        /**
         * The slot for [now], defaulting to the device clock.
         *
         * Pure, so the boundaries are unit-testable without a device.
         */
        fun current(now: LocalTime = LocalTime.now()): MomentSlot =
            entries.first { it.contains(now.hour) }
    }
}
