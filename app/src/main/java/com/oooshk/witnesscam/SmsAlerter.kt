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

/** Texts the user's emergency contacts when a recording starts. */
object SmsAlerter {
    private const val TAG = "SmsAlerter"

    fun sendStartAlert(context: Context, location: Location?) {
        val prefs = Prefs(context)
        val contacts = prefs.contactList()
        if (contacts.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS not granted; skipping alert")
            return
        }

        val text = buildString {
            append(prefs.alertMessage)
            append("\nTime: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
            if (location != null) {
                append("\nLocation: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, location.latitude, location.longitude))
            }
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
    }
}
