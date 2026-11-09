package com.music.sonariq.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny on-device record of the app's own uncaught exceptions.
 *
 * Sonariq collects nothing off-device, so when the app dies on the
 * owner's tablet there is otherwise no trace to work from: the crash
 * leaves the process before any log the app keeps is read. This
 * recorder installs a default uncaught-exception handler that appends
 * one small file per crash — timestamp, exception type, stack trace —
 * into app-private storage, keeps only the most recent few, and then
 * chains to whatever handler was installed before it, so the crash
 * itself behaves exactly as it always did.
 *
 * The Admin System Health section reads the newest entry back; that is
 * the whole interface. Nothing here leaves the device.
 */
object CrashRecorder {

    /** One recorded crash: when, what type, and the trace's first lines. */
    data class Entry(
        val timestampMs: Long,
        val exceptionType: String,
        val traceLines: List<String>,
    )

    private const val DIRECTORY_NAME = "crashlog"
    private const val MAX_ENTRIES = 3
    private const val FILE_SUFFIX = ".log"

    @Volatile
    private var installed = false

    /**
     * Installs the recording handler. Call once, early, from the
     * Application: anything installed before the rest of startup runs
     * can still be recorded if startup itself is what dies.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(dir, throwable) }
            // The crash proceeds exactly as it would have without us:
            // the previous handler (on Android, the runtime's own) takes
            // it from here; with none, the process is ended directly so
            // a dead main thread cannot leave a zombie app behind.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun record(dir: File, throwable: Throwable) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val file = File(dir, "$now$FILE_SUFFIX")
        file.writeText(buildString {
            append(now).append('\n')
            append(throwable.javaClass.name).append('\n')
            append(writer.toString())
        })
        // Keep only the newest few: a crash loop must not fill storage.
        val entries = dir.listFiles { f -> f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        entries.drop(MAX_ENTRIES).forEach { runCatching { it.delete() } }
    }

    /** The most recent recorded crash, or null when none is on record. */
    fun latest(context: Context): Entry? {
        val dir = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        val newest = dir.listFiles { f -> f.name.endsWith(FILE_SUFFIX) }
            ?.maxByOrNull { it.name }
            ?: return null
        val lines = runCatching { newest.readLines() }.getOrNull() ?: return null
        if (lines.size < 2) return null
        return Entry(
            timestampMs = lines[0].toLongOrNull() ?: newest.lastModified(),
            exceptionType = lines[1],
            traceLines = lines.drop(2),
        )
    }

    /** Deletes every recorded crash. */
    fun clear(context: Context) {
        val dir = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** "2026-11-09 14:03" for a recorded timestamp, in the device's locale conventions. */
    fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))
}
