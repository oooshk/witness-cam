package com.oooshk.witnesscam

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device error log and crash report, so problems on a real phone can be shared without adb.
 * Also detects a crash during dual-camera recording and falls back to one camera next time.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_LOG_BYTES = 256 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun logFile(context: Context) = File(context.filesDir, "diagnostics.log")
    fun crashFile(context: Context) = File(context.filesDir, "last_crash.txt")

    /** Installs the crash handler. Call once from Application.onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dual = Prefs(app).dualAttemptActive
                if (dual) disableDualAfterFailure(app)
                val report = buildString {
                    append(header(app))
                    append("Crashed on thread: ${thread.name}\n")
                    if (dual) append("Dual-camera recording was running; switched to rear camera only.\n")
                    append('\n').append(stackTrace(error))
                }
                crashFile(app).writeText(report)
                log(app, "CRASH", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A dual-camera attempt that never finished (e.g. a native crash in the camera or encoder that
     * bypassed the Java handler) is treated as a failure. Call before starting a recording.
     */
    fun checkUnfinishedDualAttempt(context: Context) {
        if (!Prefs(context).dualAttemptActive) return
        disableDualAfterFailure(context)
        if (!crashFile(context).exists()) {
            crashFile(context).writeText(
                header(context) +
                    "The app stopped unexpectedly while recording with both cameras " +
                    "(no error details were available).\n" +
                    "Switched to rear camera only.\n"
            )
        }
    }

    fun disableDualAfterFailure(context: Context) {
        val prefs = Prefs(context)
        prefs.dualAttemptActive = false
        prefs.dualCamera = false
        prefs.dualDisabledAfterCrash = true
    }

    fun log(context: Context, message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        runCatching {
            val file = logFile(context)
            if (file.length() > MAX_LOG_BYTES) {
                // Keep the newest half.
                val text = file.readText()
                file.writeText(text.substring(text.length / 2))
            }
            file.appendText(buildString {
                append(stamp.format(Date())).append("  ").append(message).append('\n')
                if (error != null) append(stackTrace(error)).append('\n')
            })
        }
    }

    /** Everything worth sending when reporting a problem. */
    fun report(context: Context): String = buildString {
        append(header(context))
        crashFile(context).takeIf { it.exists() }?.let { append("\n=== Last crash ===\n").append(it.readText()) }
        logFile(context).takeIf { it.exists() }?.let { append("\n=== Log ===\n").append(it.readText().takeLast(60_000)) }
    }

    private fun header(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return "WitnessCam $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${stamp.format(Date())}\n"
    }

    private fun stackTrace(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
