package com.oooshk.witnesscam

import android.content.Context
import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Dropbox OAuth 2 with PKCE and no redirect: the user approves in the browser, Dropbox shows a
 * one-time code, and the user pastes it back. No client secret and no redirect URI registration are
 * needed, and the app key can be entered at runtime.
 */
object DropboxAuth {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()

    /** Starts a sign-in and returns the URL to open in the browser. */
    fun authorizeUrl(context: Context, appKey: String): Uri {
        val verifier = ByteArray(48).also { SecureRandom().nextBytes(it) }.b64url()
        Prefs(context).dropboxCodeVerifier = verifier
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).b64url()
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", appKey)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /** Exchanges the pasted code for tokens. Blocking; call off the main thread. */
    fun finishSignIn(context: Context, appKey: String, code: String) {
        val prefs = Prefs(context)
        val verifier = prefs.dropboxCodeVerifier ?: throw IOException("Tap \"Open Dropbox\" first")
        val json = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code.trim())
                .add("client_id", appKey)
                .add("code_verifier", verifier)
                .build()
        )
        prefs.dropboxAppKey = appKey
        prefs.dropboxRefreshToken = json.getString("refresh_token")
        store(prefs, json)
        prefs.dropboxCodeVerifier = null
    }

    /** Returns a valid access token, refreshing it if needed. Blocking. */
    fun accessToken(context: Context, forceRefresh: Boolean = false): String = synchronized(lock) {
        val prefs = Prefs(context)
        val refresh = prefs.dropboxRefreshToken ?: throw CloudAuthRequiredException("Dropbox not connected")
        val current = prefs.dropboxAccessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < prefs.dropboxAccessExpiryMs) {
            return current
        }
        val json = try {
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", prefs.dropboxAppKey ?: "")
                    .build()
            )
        } catch (e: DropboxRejectedException) {
            // The user revoked access or deleted the app: they need to connect again.
            signOut(context)
            throw CloudAuthRequiredException("Dropbox access was revoked")
        }
        store(prefs, json)
        json.getString("access_token")
    }

    fun signOut(context: Context) {
        val prefs = Prefs(context)
        prefs.dropboxRefreshToken = null
        prefs.dropboxAccessToken = null
    }

    private fun store(prefs: Prefs, json: JSONObject) {
        prefs.dropboxAccessToken = json.getString("access_token")
        // Refresh a minute early so a token never expires mid-upload.
        prefs.dropboxAccessExpiryMs = System.currentTimeMillis() + (json.optLong("expires_in", 14_400) - 60) * 1000
    }

    private class DropboxRejectedException(msg: String) : IOException(msg)

    private fun tokenRequest(form: FormBody): JSONObject {
        val req = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(form).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 400 || resp.code == 401) throw DropboxRejectedException(errorText(body))
            if (!resp.isSuccessful) throw IOException("Dropbox HTTP ${resp.code}: ${body.take(300)}")
            return JSONObject(body)
        }
    }

    private fun errorText(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optString("error_description").ifEmpty { json.optString("error") }
    }.getOrNull()?.ifEmpty { null } ?: body.take(200)

    private fun ByteArray.b64url() =
        Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
