package com.oooshk.witnesscam

import android.content.Context

/** Thin wrapper over SharedPreferences for the user's settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("witnesscam", Context.MODE_PRIVATE)

    /** Length of each recorded chunk. Shorter chunks reach the cloud sooner. */
    var segmentSeconds: Int
        get() = sp.getInt("segment_seconds", 10)
        set(v) = sp.edit().putInt("segment_seconds", v.coerceIn(5, 60)).apply()

    var deleteAfterUpload: Boolean
        get() = sp.getBoolean("delete_after_upload", true)
        set(v) = sp.edit().putBoolean("delete_after_upload", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", false)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    /** Comma separated phone numbers that get a text when recording starts. */
    var emergencyContacts: String
        get() = sp.getString("emergency_contacts", "") ?: ""
        set(v) = sp.edit().putString("emergency_contacts", v).apply()

    var alertMessage: String
        get() = sp.getString("alert_message", null)
            ?: "I've been stopped by police and am recording. Video is uploading to my Google Drive."
        // Blank resets to the default message.
        set(v) = sp.edit().apply { if (v.isBlank()) remove("alert_message") else putString("alert_message", v) }.apply()

    var driveConnected: Boolean
        get() = sp.getBoolean("drive_connected", false)
        set(v) = sp.edit().putBoolean("drive_connected", v).apply()

    /** Cached id of the top-level "WitnessCam" folder in Drive. */
    var driveRootFolderId: String?
        get() = sp.getString("drive_root_folder", null)
        set(v) = sp.edit().putString("drive_root_folder", v).apply()

    fun contactList(): List<String> =
        emergencyContacts.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
