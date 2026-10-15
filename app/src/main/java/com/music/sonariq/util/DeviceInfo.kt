package com.music.sonariq.util

import android.os.Build

/**
 * Reads the device's own identity — the phone or TV Sonariq is running on.
 * No permissions needed; [Build] fields are always available.
 */
object DeviceInfo {

    /** e.g. "Pixel 8 Pro", "SM-S921B", "SHIELD Android TV". */
    val model: String get() = Build.MODEL ?: "Unknown"

    /** e.g. "Google", "samsung". */
    val manufacturer: String get() = Build.MANUFACTURER ?: "Unknown"

    /** e.g. "panther", "dm3q". */
    val device: String get() = Build.DEVICE ?: "Unknown"

    /** Android version, e.g. "14". */
    val androidVersion: String get() = Build.VERSION.RELEASE ?: "Unknown"

    /** SDK level, e.g. 34. */
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    /** "Google Pixel 8 Pro" — manufacturer + model, deduped. */
    val displayName: String get() {
        val mfr = manufacturer.replaceFirstChar { it.uppercase() }
        return if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"
    }

    /** True on Android TV / Google TV devices. */
    val isTv: Boolean get() =
        (Build.MODEL ?: "").contains("tv", ignoreCase = true) ||
            (Build.DEVICE ?: "").contains("tv", ignoreCase = true)
}
