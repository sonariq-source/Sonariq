package com.music.sonariq.playback

import android.content.Context
import com.music.sonariq.data.settings.AppSettings
import com.music.sonariq.data.settings.EqualizerMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-device equalizer profiles: the EQ curve follows the output, not the app.
 *
 * Headphones, the phone speaker and the car stereo want different curves, and
 * one global EQ means re-tuning every time the route changes. When
 * [AppSettings.perDeviceEqEnabled] is on, [DeviceEqController] watches the
 * active output and swaps the whole EQ state — mode, tone pad or manual bands,
 * bass/treble trims and balance — to whatever was last used on that device.
 *
 * This is real, not a second EQ: profiles are applied through the normal
 * [AppSettings] setters, so the playback service's existing
 * `applyEqualizer()` collector picks them up and hands the curve to both DSP
 * chains exactly as if the user had dragged every slider by hand.
 *
 * Devices are keyed by [AudioRouting.Kind] plus the device's own name. A
 * device id would be the precise key, but ids are only valid while the device
 * stays connected — the same headphones come back with a different id after a
 * reconnect — so a stored id would either match nothing or, eventually,
 * something else entirely. See [AudioRouting] for why.
 */
@Serializable
data class DeviceEqProfile(
    val enabled: Boolean,
    /** [EqualizerMode] name. */
    val mode: String,
    val toneX: Int,
    val toneY: Int,
    val focused: Boolean,
    val balance: Float,
    /** The seven manual-tab gains, low to high. */
    val bandsDb: List<Float>,
    val bassDb: Float,
    val trebleDb: Float,
)

/**
 * Stable key for an output. The phone speaker and wired outputs have no name
 * of their own ([AudioRouting] leaves them blank), so the kind alone names
 * them; anything with a product name gets kind + name.
 */
fun deviceKey(kind: AudioRouting.Kind, name: String): String =
    kind.name + "|" + name.ifBlank { "default" }

/** Reads the whole current EQ state into a profile. */
fun snapshotEq(): DeviceEqProfile = DeviceEqProfile(
    enabled = AppSettings.equalizerEnabled.value,
    mode = AppSettings.equalizerMode.value.name,
    toneX = AppSettings.equalizerToneX.value,
    toneY = AppSettings.equalizerToneY.value,
    focused = AppSettings.equalizerFocused.value,
    balance = AppSettings.equalizerBalance.value,
    bandsDb = AppSettings.equalizerBands.value.toList(),
    bassDb = AppSettings.equalizerBass.value,
    trebleDb = AppSettings.equalizerTreble.value,
)

/**
 * Writes a profile into the live EQ settings. Every setter persists and
 * notifies the service's EQ collector, so this is immediately audible.
 */
fun applyEqProfile(profile: DeviceEqProfile) {
    AppSettings.setEqualizerEnabled(profile.enabled)
    runCatching { EqualizerMode.valueOf(profile.mode) }
        .getOrDefault(EqualizerMode.DYNAMIC)
        .let { AppSettings.setEqualizerMode(it) }
    AppSettings.setEqualizerTone(profile.toneX, profile.toneY)
    AppSettings.setEqualizerFocused(profile.focused)
    AppSettings.setEqualizerBalance(profile.balance)
    AppSettings.setEqualizerBands(profile.bandsDb)
    AppSettings.setEqualizerBass(profile.bassDb)
    AppSettings.setEqualizerTreble(profile.trebleDb)
}

private val deviceEqJson = Json { ignoreUnknownKeys = true }

internal fun encodeDeviceProfiles(profiles: Map<String, DeviceEqProfile>): String =
    deviceEqJson.encodeToString(profiles)

internal fun decodeDeviceProfiles(raw: String?): Map<String, DeviceEqProfile> =
    runCatching { deviceEqJson.decodeFromString<Map<String, DeviceEqProfile>>(raw ?: "{}") }
        .getOrDefault(emptyMap())

/** The persisted profiles, in their own preference file — not in AppSettings. */
object DeviceEqStore {

    private const val PREFS_NAME = "device_eq_profiles"
    private const val KEY_PROFILES = "profiles"

    fun load(context: Context, key: String): DeviceEqProfile? =
        decodeDeviceProfiles(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROFILES, null),
        )[key]

    fun save(context: Context, key: String, profile: DeviceEqProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = decodeDeviceProfiles(prefs.getString(KEY_PROFILES, null)) + (key to profile)
        prefs.edit().putString(KEY_PROFILES, encodeDeviceProfiles(updated)).apply()
    }

    fun forget(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = decodeDeviceProfiles(prefs.getString(KEY_PROFILES, null)) - key
        prefs.edit().putString(KEY_PROFILES, encodeDeviceProfiles(updated)).apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROFILES).apply()
    }

    fun hasProfile(context: Context, key: String): Boolean = load(context, key) != null
}

/**
 * Swaps EQ profiles as the output route changes.
 *
 * The contract: leaving a device stores whatever the EQ looks like *now*
 * under that device, but only when it differs from what was loaded for it —
 * a route change with no tuning in between must not rewrite the profile.
 * Arriving on a device with a stored profile applies it; arriving on one
 * without leaves the current curve alone and treats it as that device's
 * starting point, so the first visit never clobbers the user's EQ with a
 * default.
 */
object DeviceEqController {

    private var lastKey: String? = null
    private var lastSnapshot: DeviceEqProfile? = null

    /** Call when the active output route changes. Cheap; safe to call often. */
    fun onRouteChanged(context: Context, kind: AudioRouting.Kind, name: String) {
        val key = deviceKey(kind, name)
        if (key == lastKey) return
        if (!AppSettings.perDeviceEqEnabled.value) {
            lastKey = key
            lastSnapshot = null
            return
        }
        val current = snapshotEq()
        val previousKey = lastKey
        if (previousKey != null && current != lastSnapshot) {
            DeviceEqStore.save(context, previousKey, current)
        }
        val stored = DeviceEqStore.load(context, key)
        if (stored != null) {
            applyEqProfile(stored)
            lastSnapshot = stored
        } else {
            lastSnapshot = current
        }
        lastKey = key
    }

    /** Forgets the current device's profile; the live curve is untouched. */
    fun forgetCurrentDevice(context: Context, kind: AudioRouting.Kind, name: String) {
        DeviceEqStore.forget(context, deviceKey(kind, name))
        lastSnapshot = snapshotEq()
    }

    /** Test and diagnostic hook. */
    internal fun resetForTest() {
        lastKey = null
        lastSnapshot = null
    }
}
