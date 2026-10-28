package com.oooshk.witnesscam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texts the user's emergency contacts about a recording. */
object SmsAlerter {
    private const val TAG = "SmsAlerter"

    fun sendStartAlert(context: Context, location: Location?) {
        send(context, buildString {
            append(Prefs(context).alertMessage)
            append("\nStarted: ").append(now())
            appendLocation(location)
        })
    }

    fun sendFootageLink(context: Context, link: String) {
        send(context, "WitnessCam: footage is uploading live here (refresh for new clips):\n$link")
    }

    fun sendLocationUpdate(context: Context, location: Location?, startedAtMs: Long) {
        val minutes = (System.currentTimeMillis() - startedAtMs) / 60_000
        send(context, buildString {
            append("WitnessCam: still recording ($minutes min). ").append(now())
            appendLocation(location)
        })
    }

    fun sendStopAlert(context: Context, location: Location?, startedAtMs: Long) {
        val minutes = (System.currentTimeMillis() - startedAtMs + 30_000) / 60_000
        send(context, buildString {
            append("WitnessCam: recording stopped after $minutes min. ").append(now())
            appendLocation(location)
        })
    }

    fun sendTest(context: Context): Boolean = send(context, "WitnessCam test: this is a test alert. No action needed.")

    /** Returns false if there are no contacts or SMS permission is missing. */
    private fun send(context: Context, text: String): Boolean {
        val contacts = Prefs(context).contactList()
        if (contacts.isEmpty()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS not granted; skipping alert")
            return false
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        for (number in contacts) {
            try {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to text $number", e)
            }
        }
        return true
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date())

    private fun StringBuilder.appendLocation(location: Location?) {
        if (location != null) {
            append("\nLocation: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, location.latitude, location.longitude))
        } else {
            append("\nLocation: unavailable")
        }
    }
}
