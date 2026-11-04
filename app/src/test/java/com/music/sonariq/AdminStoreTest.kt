package com.music.sonariq

import com.music.sonariq.data.admin.AdminAnnouncement
import com.music.sonariq.data.admin.AuditEntry
import com.music.sonariq.data.admin.AuditTrail
import com.music.sonariq.data.admin.OwnerClaim
import com.music.sonariq.data.admin.OwnerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdminStoreTest {

    @Test fun `first claim wins and carries the generated id`() {
        val result = OwnerClaim.decide(existing = null, candidateId = "id-1", nowMs = 100L, label = "  Alex  ")
        val claimed = result as OwnerClaim.Result.Claimed
        assertEquals("id-1", claimed.record.id)
        assertEquals(100L, claimed.record.claimedAtMs)
        // Labels are trimmed; the id, never the label, is the identity.
        assertEquals("Alex", claimed.record.label)
    }

    @Test fun `blank label falls back to Owner`() {
        val result = OwnerClaim.decide(existing = null, candidateId = "id-1", nowMs = 1L, label = "   ")
        assertEquals("Owner", (result as OwnerClaim.Result.Claimed).record.label)
    }

    @Test fun `second claim always loses to the first record`() {
        val first = OwnerRecord(id = "id-1", claimedAtMs = 100L, label = "First")
        val result = OwnerClaim.decide(existing = first, candidateId = "id-2", nowMs = 200L, label = "Second")
        val refused = result as OwnerClaim.Result.AlreadyClaimed
        // The surviving record is the original, untouched: id, time and label.
        assertEquals(first, refused.existing)
    }

    @Test fun `blank candidate id is rejected`() {
        try {
            OwnerClaim.decide(existing = null, candidateId = "  ", nowMs = 1L, label = "X")
            fail("blank owner id must not claim")
        } catch (expected: IllegalArgumentException) {
            // Expected: identity must be a generated id, never blank.
        }
    }

    @Test fun `audit trail is newest first and bounded`() {
        var trail = emptyList<AuditEntry>()
        for (i in 1..(AuditTrail.MAX_ENTRIES + 25)) {
            trail = AuditTrail.appended(trail, AuditEntry(i.toLong(), "action-$i", "target", "done"))
        }
        assertEquals(AuditTrail.MAX_ENTRIES, trail.size)
        assertEquals("action-${AuditTrail.MAX_ENTRIES + 25}", trail.first().action)
        assertEquals("action-26", trail.last().action)
    }

    @Test fun `announcement liveness honours expiry`() {
        val open = AdminAnnouncement("a", "t", "b", createdAtMs = 10L, expiresAtMs = null)
        assertTrue(open.isLive(nowMs = Long.MAX_VALUE))
        val expiring = AdminAnnouncement("b", "t", "b", createdAtMs = 10L, expiresAtMs = 100L)
        assertTrue(expiring.isLive(nowMs = 99L))
        assertFalse(expiring.isLive(nowMs = 100L))
    }
}
