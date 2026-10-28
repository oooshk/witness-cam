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

object Cloud {
    const val GOOGLE_DRIVE = "google"
    const val DROPBOX = "dropbox"

    fun provider(context: Context): String = Prefs(context).cloudProvider

    fun isConnected(context: Context): Boolean {
        val prefs = Prefs(context)
        return when (prefs.cloudProvider) {
            DROPBOX -> prefs.dropboxRefreshToken != null
            else -> prefs.driveConnected
        }
    }

    /** Returns an uploader for the selected provider, or throws if the account isn't connected. */
    suspend fun uploader(context: Context): CloudUploader = when (provider(context)) {
        DROPBOX -> DropboxUploader(context.applicationContext)
        else -> {
            val token = DriveAuth.tokenSilently(context)
                ?: throw CloudAuthRequiredException("Google Drive not authorized")
            GoogleDriveUploader(context.applicationContext, token)
        }
    }
}

class GoogleDriveUploader(private val context: Context, private var token: String) : CloudUploader {

    override suspend fun upload(chunk: ChunkUpload): String = withFreshToken { drive ->
        val folderId = DriveFolders.sessionFolder(context, drive, chunk.session)
        val uploaded = drive.upload(
            chunk.file, chunk.file.name, folderId, "video/mp4", chunk.description, chunk.properties,
        )
        // Make sure Drive stored exactly the bytes we recorded before anything is deleted locally.
        val verified = when {
            uploaded.sha256 != null -> uploaded.sha256.equals(chunk.sha256, ignoreCase = true)
            uploaded.md5 != null -> uploaded.md5.equals(chunk.md5, ignoreCase = true)
            else -> true
        }
        if (!verified) throw IOException("Checksum mismatch for ${chunk.file.name}")
        uploaded.id
    }

    override suspend fun shareSessionLink(session: File): String = withFreshToken { drive ->
        val folderId = DriveFolders.sessionFolder(context, drive, session)
        drive.shareWithAnyoneWithLink(folderId)
        DriveFolders.folderLink(folderId)
    }

    private suspend fun <T> withFreshToken(block: (DriveClient) -> T): T {
        try {
            return run(block)
        } catch (e: AuthExpiredException) {
            DriveAuth.invalidate(context, token)
            token = DriveAuth.tokenSilently(context) ?: throw CloudAuthRequiredException("Google Drive access revoked")
            return run(block)
        } catch (e: IOException) {
            // A 404 usually means the user deleted our Drive folder; forget cached ids so it's recreated.
            if (e.message?.startsWith("Drive HTTP 404") == true) DriveFolders.forgetCachedIds(context)
            throw e
        }
    }

    private fun <T> run(block: (DriveClient) -> T): T = block(DriveClient(token))
}
