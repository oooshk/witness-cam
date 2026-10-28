package com.oooshk.witnesscam

import android.content.Context

/** Thin wrapper over SharedPreferences for the user's settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("witnesscam", Context.MODE_PRIVATE)

    /** Length of each recorded chunk. Shorter chunks reach the cloud sooner. */
    var segmentSeconds: Int
        get() = sp.getInt("segment_seconds", 10)
        set(v) = sp.edit().putInt("segment_seconds", v.coerceIn(5, 60)).apply()

    /** Also record the front camera (to separate files) when the phone supports it. */
    var dualCamera: Boolean
        get() = sp.getBoolean("dual_camera", true)
        set(v) = sp.edit().putBoolean("dual_camera", v).apply()

    /** The home-screen widget needs a confirming second tap. */
    var widgetDoubleTap: Boolean
        get() = sp.getBoolean("widget_double_tap", true)
        set(v) = sp.edit().putBoolean("widget_double_tap", v).apply()

    /** While in the future, the widget is armed and the next tap acts. */
    var widgetArmedUntilMs: Long
        get() = sp.getLong("widget_armed_until", 0)
        set(v) = sp.edit().putLong("widget_armed_until", v).apply()

    /** Off by default so recordings can be reviewed on the phone. */
    var deleteAfterUpload: Boolean
        get() = sp.getBoolean("delete_after_upload", false)
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

    /** Which cloud uploads go to: [Cloud.GOOGLE_DRIVE] or [Cloud.DROPBOX]. */
    var cloudProvider: String
        get() = sp.getString("cloud_provider", Cloud.GOOGLE_DRIVE) ?: Cloud.GOOGLE_DRIVE
        set(v) = sp.edit().putString("cloud_provider", v).apply()

    var dropboxAppKey: String?
        get() = sp.getString("dropbox_app_key", null)
        set(v) = sp.edit().putString("dropbox_app_key", v?.trim()).apply()

    var dropboxRefreshToken: String?
        get() = sp.getString("dropbox_refresh_token", null)
        set(v) = sp.edit().putString("dropbox_refresh_token", v).apply()

    var dropboxAccessToken: String?
        get() = sp.getString("dropbox_access_token", null)
        set(v) = sp.edit().putString("dropbox_access_token", v).apply()

    var dropboxAccessExpiryMs: Long
        get() = sp.getLong("dropbox_access_expiry", 0)
        set(v) = sp.edit().putLong("dropbox_access_expiry", v).apply()

    /** PKCE verifier for a Dropbox sign-in that's waiting for the user to paste the code. */
    var dropboxCodeVerifier: String?
        get() = sp.getString("dropbox_code_verifier", null)
        set(v) = sp.edit().putString("dropbox_code_verifier", v).apply()

    /** Cached id of the top-level "WitnessCam" folder in Drive. */
    var driveRootFolderId: String?
        get() = sp.getString("drive_root_folder", null)
        set(v) = sp.edit().putString("drive_root_folder", v).apply()

    /** Text contacts again when recording stops. */
    var smsOnStop: Boolean
        get() = sp.getBoolean("sms_on_stop", true)
        set(v) = sp.edit().putBoolean("sms_on_stop", v).apply()

    /** While recording, text an updated location every N minutes (0 = off). */
    var locationUpdateMinutes: Int
        get() = sp.getInt("location_update_minutes", 0)
        set(v) = sp.edit().putInt("location_update_minutes", v.coerceIn(0, 120)).apply()

    /** Make the session's Drive folder viewable by link and text that link to contacts. */
    var shareFootageLink: Boolean
        get() = sp.getBoolean("share_footage_link", false)
        set(v) = sp.edit().putBoolean("share_footage_link", v).apply()

    val hasPin: Boolean get() = sp.contains("pin_hash")

    /** Stores a salted PBKDF2 hash of [pin]; a blank pin removes protection. */
    fun setPin(pin: String) {
        if (pin.isBlank()) {
            sp.edit().remove("pin_hash").remove("pin_salt").apply()
            return
        }
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("pin_salt", salt.b64())
            .putString("pin_hash", hashPin(pin, salt).b64())
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null)?.unb64() ?: return true
        val expected = sp.getString("pin_hash", null)?.unb64() ?: return true
        return java.security.MessageDigest.isEqual(hashPin(pin, salt), expected)
    }

    /** Wrong-PIN lockout state, persisted so restarting the app doesn't reset it. */
    var pinFailures: Int
        get() = sp.getInt("pin_failures", 0)
        set(v) = sp.edit().putInt("pin_failures", v).apply()

    var pinLockedUntilMs: Long
        get() = sp.getLong("pin_locked_until", 0)
        set(v) = sp.edit().putLong("pin_locked_until", v).apply()

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.b64() = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    private fun String.unb64() = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    fun contactList(): List<String> =
        emergencyContacts.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
