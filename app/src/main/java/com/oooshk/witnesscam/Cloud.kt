package com.oooshk.witnesscam

import android.content.Context
import java.io.File
import java.io.IOException

/** The user must (re)connect their cloud account before uploads can continue. */
class CloudAuthRequiredException(message: String) : IOException(message)

/** One recorded chunk plus everything worth storing alongside it. */
class ChunkUpload(
    val session: File,
    val file: File,
    val sha256: String,
    val md5: String,
    val description: String,
    val properties: Map<String, String>,
)

/** A cloud storage destination. Implementations must verify the stored bytes before returning. */
interface CloudUploader {
    /** Uploads [chunk] and returns the remote id or path. */
    suspend fun upload(chunk: ChunkUpload): String

    /** Makes the session's folder viewable by link and returns that link. */
    suspend fun shareSessionLink(session: File): String
}

/** Uploads go to the user's Dropbox (the app's own folder in it). */
object Cloud {
    fun isConnected(context: Context): Boolean = Prefs(context).dropboxRefreshToken != null

    /** Returns the uploader, or throws if the user hasn't signed in to Dropbox. */
    fun uploader(context: Context): CloudUploader {
        if (!isConnected(context)) throw CloudAuthRequiredException("Dropbox not connected")
        return DropboxUploader(context.applicationContext)
    }
}
