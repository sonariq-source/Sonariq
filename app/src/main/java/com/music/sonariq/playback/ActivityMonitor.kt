package com.music.sonariq.playback

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ActivityMonitor: what the listener is physically doing, from real device
 * signals only — never a guess.
 *
 * Two signals:
 *
 * - **Step cadence.** [Sensor.TYPE_STEP_DETECTOR] events are timestamped
 *   into a rolling two-minute window. A cadence of 100+ steps a minute reads
 *   as [ActivityState.RUNNING], 40+ as [ActivityState.WALKING]. The sensor
 *   needs the ACTIVITY_RECOGNITION runtime permission on API 29+; without
 *   it — or without a step sensor at all — the monitor registers nothing
 *   and reports [ActivityState.UNAVAILABLE], and the session engine leaves
 *   the music alone.
 * - **Car audio.** Audio routed to a Bluetooth A2DP output whose name
 *   carries the word "car", or whose Bluetooth class is a car-audio or
 *   hands-free kit, reads as [ActivityState.DRIVING]. Motion alone never
 *   says driving: a passenger on a train walks the same cadence as a
 *   driver sitting still.
 *
 * Everything is guarded: any failure — no permission, no sensor, a
 * Bluetooth stack that refuses to answer — lands on [ActivityState.UNAVAILABLE]
 * or [ActivityState.UNKNOWN] rather than a crash, and the engine treats
 * both as "change nothing".
 */
object ActivityMonitor {

    enum class ActivityState { UNAVAILABLE, UNKNOWN, WALKING, RUNNING, DRIVING }

    /** How far back step events count towards the cadence. */
    private const val WINDOW_MS = 120_000L

    /** Steps a minute at or above which the listener is walking. */
    private const val WALK_CADENCE = 40f

    /** Steps a minute at or above which the listener is running. */
    private const val RUN_CADENCE = 100f

    private val _state = MutableStateFlow(ActivityState.UNAVAILABLE)

    /** The current activity reading; [ActivityState.UNAVAILABLE] until [start] succeeds. */
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    /** Timestamps (elapsed realtime) of the steps inside the rolling window. */
    private val stepTimes = ArrayDeque<Long>()

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            // TYPE_STEP_DETECTOR fires once per step taken.
            if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return
            stepTimes.addLast(System.currentTimeMillis())
            evaluateSteps()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Starts watching. Idempotent: a second call while running does
     * nothing. When the step permission is missing or the device has no
     * step detector, nothing is registered and [state] stays
     * [ActivityState.UNAVAILABLE] — the honest "this can't run here".
     */
    @Synchronized
    fun start(context: Context) {
        if (listener != null) return
        if (!hasStepPermission(context)) {
            _state.value = ActivityState.UNAVAILABLE
            return
        }
        val manager = runCatching {
            context.applicationContext.getSystemService(SensorManager::class.java)
        }.getOrNull()
        val sensor = runCatching { manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }.getOrNull()
        if (manager == null || sensor == null) {
            _state.value = ActivityState.UNAVAILABLE
            return
        }
        val registered = runCatching {
            manager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (!registered) {
            _state.value = ActivityState.UNAVAILABLE
            return
        }
        sensorManager = manager
        listener = stepListener
        stepTimes.clear()
        if (_state.value == ActivityState.UNAVAILABLE) _state.value = ActivityState.UNKNOWN
    }

    /** Stops watching and unregisters the step sensor. */
    @Synchronized
    fun stop() {
        listener?.let { active ->
            runCatching { sensorManager?.unregisterListener(active) }
        }
        listener = null
        sensorManager = null
        stepTimes.clear()
        _state.value = ActivityState.UNAVAILABLE
    }

    /**
     * Re-derives [state] from the signals as they stand right now: prunes
     * the step window (steps stop arriving when the listener stops moving,
     * so staleness has to be checked on a clock, not on the next event) and
     * re-checks the car-audio route. Called by the session engine on its
     * 30-second tick; cheap and safe to call when never started.
     */
    fun refresh(context: Context) {
        if (isCarAudioActive(context.applicationContext)) {
            _state.value = ActivityState.DRIVING
            return
        }
        if (_state.value == ActivityState.DRIVING) _state.value = ActivityState.UNKNOWN
        evaluateSteps()
    }

    /** Cadence from the rolling window, and the state it implies. */
    private fun evaluateSteps() {
        if (listener == null) return
        if (_state.value == ActivityState.DRIVING) return
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (stepTimes.isNotEmpty() && stepTimes.first() < cutoff) stepTimes.removeFirst()
        val cadence = stepTimes.size / (WINDOW_MS / 60_000f)
        _state.value = when {
            cadence >= RUN_CADENCE -> ActivityState.RUNNING
            cadence >= WALK_CADENCE -> ActivityState.WALKING
            else -> ActivityState.UNKNOWN
        }
    }

    /**
     * True when media audio is routed to a Bluetooth device that presents
     * as a car kit: its output name carries "car" as a whole word (so a
     * "Cardo" headset doesn't count), or — where the Bluetooth class can
     * be read, which needs BLUETOOTH_CONNECT — its class is car audio or
     * hands-free. Any doubt answers false: this only ever *adds* a
     * driving reading, never invents one.
     */
    private fun isCarAudioActive(context: Context): Boolean {
        val manager = runCatching { context.getSystemService(AudioManager::class.java) }
            .getOrNull() ?: return false
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return false
        val bluetoothClassReadable = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        for (device in outputs) {
            if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) continue
            val name = device.productName?.toString().orEmpty()
            if (name.lowercase().split(Regex("[^a-z0-9]+")).contains("car")) return true
            if (!bluetoothClassReadable) continue
            val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: continue
            val remote = runCatching {
                BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            }.getOrNull() ?: continue
            val deviceClass = runCatching { remote.bluetoothClass?.deviceClass }.getOrNull()
                ?: continue
            if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
            ) {
                return true
            }
        }
        return false
    }

    /** The step sensor's runtime permission, required from API 29 up. */
    private fun hasStepPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
