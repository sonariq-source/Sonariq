package com.music.sonariq.data.admin

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The Sonariq owner record and the administration trail, kept on this device.
 *
 * ## What this is, and what it is not
 *
 * Sonariq has no account server: there is no Sonariq registration, no user
 * database and no backend that could hold an owner record for every install.
 * The accounts the app knows are external sessions (a video-service session,
 * a chat integration) held in the encrypted [com.music.sonariq.auth.AuthStore].
 * So ownership here is a *device* claim: the first person to set up the admin
 * panel on this installation becomes this device's owner, permanently, and
 * the panel's data is this device's own real data — listening aggregates,
 * caches, downloads and settings.
 *
 * The claim is written exactly once. [claimOwner] re-reads the stored record
 * inside a process-wide lock and refuses if one exists, so two racing setups
 * cannot both win, and nothing in the app ever rewrites or clears the record
 * afterwards — profile edits included. The id is a generated stable UUID,
 * never a username or display name, so renaming a profile cannot move or
 * lose ownership.
 *
 * The honest limit, stated in the panel itself: a record in app-private
 * storage proves ownership to this app on this device. Against a modified
 * app or a rooted device it cannot be a guarantee — that would take a
 * trusted backend Sonariq does not run — so the panel never claims
 * server-grade security.
 */
data class OwnerRecord(
    val id: String,
    val claimedAtMs: Long,
    /** The owner's own label for themselves, chosen at claim time. */
    val label: String,
)

/** The pure claim decision, separated from storage so it can be tested. */
object OwnerClaim {

    sealed interface Result {
        data class Claimed(val record: OwnerRecord) : Result
        data class AlreadyClaimed(val existing: OwnerRecord) : Result
    }

    /**
     * Decides a claim against the record that already exists (or none).
     * A claim succeeds only when [existing] is null; the candidate id must
     * be a real generated id, never blank.
     */
    fun decide(existing: OwnerRecord?, candidateId: String, nowMs: Long, label: String): Result {
        if (existing != null) return Result.AlreadyClaimed(existing)
        require(candidateId.isNotBlank()) { "Owner id must not be blank" }
        return Result.Claimed(
            OwnerRecord(
                id = candidateId,
                claimedAtMs = nowMs,
                label = label.trim().ifBlank { "Owner" },
            ),
        )
    }
}

@Serializable
data class AuditEntry(
    val atMs: Long,
    val action: String,
    val target: String,
    val result: String,
)

@Serializable
data class AdminAnnouncement(
    val id: String,
    val title: String,
    val body: String,
    val createdAtMs: Long,
    /** Null means the announcement does not expire. */
    val expiresAtMs: Long? = null,
) {
    fun isLive(nowMs: Long): Boolean = expiresAtMs == null || expiresAtMs > nowMs
}

/** Pure audit-trail shaping: newest first, bounded, nothing sensitive stored. */
object AuditTrail {
    const val MAX_ENTRIES = 200

    fun appended(existing: List<AuditEntry>, entry: AuditEntry): List<AuditEntry> =
        (listOf(entry) + existing).take(MAX_ENTRIES)
}

object AdminStore {

    private const val PREFS_NAME = "sonariq_admin"
    private const val KEY_OWNER_ID = "owner_id"
    private const val KEY_OWNER_CLAIMED_AT = "owner_claimed_at"
    private const val KEY_OWNER_LABEL = "owner_label"
    private const val KEY_AUDIT = "audit_log"
    private const val KEY_ANNOUNCEMENTS = "announcements"

    private val json = Json { ignoreUnknownKeys = true }
    private val claimLock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The device owner, or null while ownership has never been claimed. */
    fun owner(context: Context): OwnerRecord? {
        val store = prefs(context)
        val id = store.getString(KEY_OWNER_ID, null) ?: return null
        return OwnerRecord(
            id = id,
            claimedAtMs = store.getLong(KEY_OWNER_CLAIMED_AT, 0L),
            label = store.getString(KEY_OWNER_LABEL, null) ?: "Owner",
        )
    }

    /**
     * Claims ownership for this device, exactly once. The read, the decision
     * and the write happen inside one lock and the write is committed
     * synchronously, so a second claim — racing or later — always loses to
     * the record that is already there.
     */
    fun claimOwner(context: Context, label: String): OwnerClaim.Result = synchronized(claimLock) {
        val store = prefs(context)
        val existing = owner(context)
        val result = OwnerClaim.decide(
            existing = existing,
            candidateId = UUID.randomUUID().toString(),
            nowMs = System.currentTimeMillis(),
            label = label,
        )
        if (result is OwnerClaim.Result.Claimed) {
            val record = result.record
            store.edit()
                .putString(KEY_OWNER_ID, record.id)
                .putLong(KEY_OWNER_CLAIMED_AT, record.claimedAtMs)
                .putString(KEY_OWNER_LABEL, record.label)
                .commit()
            recordAuditLocked(context, "owner.claim", "this device", "claimed")
        }
        result
    }

    fun audit(context: Context): List<AuditEntry> =
        runCatching {
            json.decodeFromString<List<AuditEntry>>(prefs(context).getString(KEY_AUDIT, null) ?: "[]")
        }.getOrDefault(emptyList())

    fun recordAudit(context: Context, action: String, target: String, result: String) {
        synchronized(claimLock) { recordAuditLocked(context, action, target, result) }
    }

    private fun recordAuditLocked(context: Context, action: String, target: String, result: String) {
        val entry = AuditEntry(
            atMs = System.currentTimeMillis(),
            action = action,
            target = target,
            result = result,
        )
        val updated = AuditTrail.appended(audit(context), entry)
        prefs(context).edit().putString(KEY_AUDIT, json.encodeToString(updated)).apply()
    }

    fun announcements(context: Context): List<AdminAnnouncement> =
        runCatching {
            json.decodeFromString<List<AdminAnnouncement>>(
                prefs(context).getString(KEY_ANNOUNCEMENTS, null) ?: "[]",
            )
        }.getOrDefault(emptyList())

    /** Publishes an announcement to this device's store and audits it. */
    fun publishAnnouncement(
        context: Context,
        title: String,
        body: String,
        expiresAtMs: Long?,
    ): AdminAnnouncement {
        val announcement = AdminAnnouncement(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            body = body.trim(),
            createdAtMs = System.currentTimeMillis(),
            expiresAtMs = expiresAtMs,
        )
        val updated = listOf(announcement) + announcements(context)
        prefs(context).edit().putString(KEY_ANNOUNCEMENTS, json.encodeToString(updated)).apply()
        recordAudit(context, "announcement.publish", announcement.title, "published")
        return announcement
    }

    fun deleteAnnouncement(context: Context, id: String) {
        val current = announcements(context)
        val removed = current.firstOrNull { it.id == id } ?: return
        val updated = current.filterNot { it.id == id }
        prefs(context).edit().putString(KEY_ANNOUNCEMENTS, json.encodeToString(updated)).apply()
        recordAudit(context, "announcement.delete", removed.title, "deleted")
    }
}
