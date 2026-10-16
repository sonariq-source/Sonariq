package com.music.sonariq

import com.music.sonariq.data.mood.MomentSlot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class MomentSlotTest {

    private fun slotAt(hour: Int, minute: Int = 0) =
        MomentSlot.current(LocalTime.of(hour, minute))

    @Test
    fun `every hour of the day maps to exactly one slot`() {
        val slots = (0..23).map { slotAt(it) }.toSet()
        assertEquals(MomentSlot.entries.toSet(), slots)
    }

    @Test
    fun `slot boundaries follow the documented ranges`() {
        // 05:00-11:00 morning energy
        assertEquals(MomentSlot.MORNING, slotAt(5))
        assertEquals(MomentSlot.MORNING, slotAt(10, 59))
        // 11:00-15:00 midday focus
        assertEquals(MomentSlot.MIDDAY, slotAt(11))
        assertEquals(MomentSlot.MIDDAY, slotAt(14, 59))
        // 15:00-19:00 afternoon drive
        assertEquals(MomentSlot.AFTERNOON, slotAt(15))
        assertEquals(MomentSlot.AFTERNOON, slotAt(18, 59))
        // 19:00-23:00 evening chill
        assertEquals(MomentSlot.EVENING, slotAt(19))
        assertEquals(MomentSlot.EVENING, slotAt(22, 59))
        // 23:00-05:00 night vibes, wrapping past midnight
        assertEquals(MomentSlot.NIGHT, slotAt(23))
        assertEquals(MomentSlot.NIGHT, slotAt(0))
        assertEquals(MomentSlot.NIGHT, slotAt(4, 59))
    }
}
