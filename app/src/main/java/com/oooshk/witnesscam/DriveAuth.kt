package com.oooshk.witnesscam

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google Drive authorization via Google Identity Services. The app only asks for the
 * drive.file scope, so it can see and manage the files it creates and nothing else in Drive.
 */
object DriveAuth {
    private val DRIVE_FILE = Scope("https://www.googleapis.com/auth/drive.file")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(DRIVE_FILE)).build()

    /** Returns an access token without UI, or null if the user must (re)grant access. */
    suspend fun tokenSilently(context: Context): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /** Drops a token the server rejected so the next request fetches a fresh one. */
    suspend fun invalidate(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}
