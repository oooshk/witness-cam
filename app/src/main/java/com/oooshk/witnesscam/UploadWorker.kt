package com.oooshk.witnesscam

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Drains the on-device chunk queue to the selected cloud, oldest first, verifying each upload. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val uploader = try {
            Cloud.uploader(applicationContext)
        } catch (e: CloudAuthRequiredException) {
            recordError(prefs, "Cloud account not connected", e)
            Notifications.cloudNeedsAuth(applicationContext)
            return@withContext Result.failure()
        }

        var failed = 0
        for (session in ChunkStore.sessions(applicationContext)) {
            for (chunk in ChunkStore.readyChunks(session)) {
                try {
                    uploadChunk(uploader, prefs, session, chunk)
                    prefs.lastUploadError = null
                    prefs.lastUploadAtMs = System.currentTimeMillis()
                } catch (e: CloudAuthRequiredException) {
                    recordError(prefs, "Cloud account not connected", e)
                    Notifications.cloudNeedsAuth(applicationContext)
                    return@withContext Result.failure()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad chunk mustn't block the rest; it's retried on the next run.
                    failed++
                    recordError(prefs, "Upload of ${session.name}/${chunk.name} failed", e)
                }
                RecordingService.notifyUploadProgress(applicationContext)
            }
        }
        if (failed > 0) Result.retry() else Result.success()
    }

    private fun recordError(prefs: Prefs, what: String, e: Throwable) {
        prefs.lastUploadError = "$what: ${e.message ?: e.javaClass.simpleName}"
        Diagnostics.log(applicationContext, what, e)
    }

    /** Only used before Android 12, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            Notifications.UPLOAD_ID,
            Notifications.uploading(applicationContext),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private suspend fun uploadChunk(uploader: CloudUploader, prefs: Prefs, session: File, chunk: File) {
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

        val remote = uploader.upload(ChunkUpload(session, chunk, sha256, md5, description, props))
        Log.i(TAG, "Uploaded ${session.name}/${chunk.name} -> $remote")

        File(session, "uploaded.log").appendText("${chunk.name}\t$remote\t$sha256\n")
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

        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

        /**
         * Starts uploading right away: skips any retry back-off that's waiting, but doesn't
         * interrupt an upload that's already running.
         */
        suspend fun uploadNow(context: Context) = withContext(Dispatchers.IO) {
            val running = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE).get()
                .any { it.state == WorkInfo.State.RUNNING }
            enqueue(context, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val network = if (Prefs(context).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                // Ask Android to run it now rather than whenever battery management allows.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy, request)
        }
    }
}
