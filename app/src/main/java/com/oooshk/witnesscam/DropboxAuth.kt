package com.oooshk.witnesscam

import android.content.Context
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Dropbox sign-in. The app key is built into the app, so users just tap "Sign in with Dropbox",
 * approve in the Dropbox app or browser, and come straight back (OAuth 2 PKCE via the official
 * SDK; no client secret). Tokens are then refreshed here with plain HTTP.
 */
object DropboxAuth {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()

    /** Permissions requested; they must also be ticked on the Dropbox app's Permissions tab. */
    private val SCOPES = listOf(
        "account_info.read",
        "files.metadata.read",
        "files.content.write",
        "sharing.read",
        "sharing.write",
    )

    val appKey: String get() = BuildConfig.DROPBOX_APP_KEY

    /** False for a build made without a Dropbox app key. */
    val available: Boolean get() = appKey.isNotEmpty()

    /** Opens Dropbox (app or browser) to approve access. Call [finishSignIn] from onResume. */
    fun startSignIn(context: Context) {
        Prefs(context).dropboxSignInPending = true
        Auth.startOAuth2PKCE(context, appKey, DbxRequestConfig("WitnessCam"), SCOPES)
    }

    /**
     * Completes a sign-in started with [startSignIn], if the user just came back from Dropbox.
     * Returns true when a new account was connected.
     */
    fun finishSignIn(context: Context): Boolean {
        val prefs = Prefs(context)
        if (!prefs.dropboxSignInPending) return false
        prefs.dropboxSignInPending = false
        val credential = Auth.getDbxCredential() ?: return false
        prefs.dropboxRefreshToken = credential.refreshToken ?: return false
        prefs.dropboxAccessToken = credential.accessToken
        // Refresh a minute early so a token never expires mid-upload.
        prefs.dropboxAccessExpiryMs = (credential.expiresAt ?: 0L) - 60_000
        prefs.dropboxAccountName = null
        return true
    }

    /** Returns a valid access token, refreshing it if needed. Blocking. */
    fun accessToken(context: Context, forceRefresh: Boolean = false): String = synchronized(lock) {
        val prefs = Prefs(context)
        val refresh = prefs.dropboxRefreshToken ?: throw CloudAuthRequiredException("Dropbox not connected")
        val current = prefs.dropboxAccessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < prefs.dropboxAccessExpiryMs) {
            return current
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", appKey)
            .build()
        val req = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(form).build()
        val json = http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 400 || resp.code == 401) {
                // The user revoked access or the app was removed: they need to sign in again.
                signOut(context)
                throw CloudAuthRequiredException("Dropbox access was revoked")
            }
            if (!resp.isSuccessful) throw IOException("Dropbox HTTP ${resp.code}: ${body.take(300)}")
            JSONObject(body)
        }
        prefs.dropboxAccessToken = json.getString("access_token")
        prefs.dropboxAccessExpiryMs = System.currentTimeMillis() + (json.optLong("expires_in", 14_400) - 60) * 1000
        json.getString("access_token")
    }

    /** The signed-in account's email (or name), fetched once and cached. Blocking. */
    fun accountName(context: Context): String? {
        val prefs = Prefs(context)
        prefs.dropboxAccountName?.let { return it }
        if (prefs.dropboxRefreshToken == null) return null
        return runCatching {
            val req = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer ${accessToken(context)}")
                // No-argument RPC endpoints take a literal JSON null.
                .post("null".toByteArray().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body!!.string())
                json.optString("email").ifEmpty { json.optJSONObject("name")?.optString("display_name") }
            }
        }.getOrNull()?.also { prefs.dropboxAccountName = it }
    }

    fun signOut(context: Context) {
        val prefs = Prefs(context)
        prefs.dropboxRefreshToken = null
        prefs.dropboxAccessToken = null
        prefs.dropboxAccountName = null
    }
}
