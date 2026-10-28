package com.oooshk.witnesscam

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Drains the on-device chunk queue to Google Drive, oldest first, verifying each upload. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        var token = DriveAuth.tokenSilently(applicationContext)
        if (token == null) {
            Log.w(TAG, "Drive not authorized; chunks stay on the device")
            Notifications.driveNeedsAuth(applicationContext)
            return@withContext Result.failure()
        }

        try {
            for (session in ChunkStore.sessions(applicationContext)) {
                for (chunk in ChunkStore.readyChunks(session)) {
                    try {
                        uploadChunk(DriveClient(token!!), prefs, session, chunk)
                    } catch (e: AuthExpiredException) {
                        DriveAuth.invalidate(applicationContext, token!!)
                        token = DriveAuth.tokenSilently(applicationContext) ?: return@withContext Result.retry()
                        uploadChunk(DriveClient(token!!), prefs, session, chunk)
                    }
                    RecordingService.notifyUploadProgress(applicationContext)
                }
                ChunkStore.pruneIfDone(session)
            }
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Upload failed, will retry", e)
            Result.retry()
        }
    }

    private fun uploadChunk(drive: DriveClient, prefs: Prefs, session: File, chunk: File) {
        val rootId = prefs.driveRootFolderId ?: drive.ensureFolder(ROOT_FOLDER, null).also {
            prefs.driveRootFolderId = it
        }
        val idFile = ChunkStore.folderIdFile(session)
        val folderId = idFile.takeIf { it.exists() }?.readText()?.trim()
            ?: drive.ensureFolder(session.name, rootId).also { idFile.writeText(it) }

        val (sha256, md5) = ChunkStore.hashes(chunk)
        val meta = ChunkStore.readMeta(chunk)
        val description = buildString {
            append("WitnessCam recording chunk\n")
            append("Session: ${session.name}\n")
            meta.optString("startedAt").takeIf { it.isNotEmpty() }?.let { append("Chunk started: $it\n") }
            meta.optString("location").takeIf { it.isNotEmpty() }?.let { append("Location: $it\n") }
            append("SHA-256 (device): $sha256")
        }
        val props = mapOf(
            "sha256" to sha256,
            "session" to session.name,
            "startedAtMs" to meta.optLong("startedAtMs").toString(),
        )

        val uploaded = drive.upload(chunk, chunk.name, folderId, "video/mp4", description, props)
        // Make sure Drive stored exactly the bytes we recorded before deleting anything locally.
        val verified = when {
            uploaded.sha256 != null -> uploaded.sha256.equals(sha256, ignoreCase = true)
            uploaded.md5 != null -> uploaded.md5.equals(md5, ignoreCase = true)
            else -> true
        }
        if (!verified) throw IOException("Checksum mismatch for ${chunk.name}")
        Log.i(TAG, "Uploaded ${session.name}/${chunk.name} -> ${uploaded.id}")

        File(session, "uploaded.log").appendText("${chunk.name}\t${uploaded.id}\t$sha256\n")
        if (prefs.deleteAfterUpload) {
            chunk.delete()
            ChunkStore.metaFile(chunk).delete()
        } else {
            chunk.renameTo(File(session, chunk.name + ".uploaded"))
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val UNIQUE = "witnesscam-upload"
        const val ROOT_FOLDER = "WitnessCam"

        fun enqueue(context: Context) {
            val network = if (Prefs(context).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
