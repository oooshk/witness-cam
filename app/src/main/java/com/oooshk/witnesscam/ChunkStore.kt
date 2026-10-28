package com.oooshk.witnesscam

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * On-device queue of recorded chunks waiting for upload.
 *
 * Layout: pending/<session>/seg_0001.mp4 (+ seg_0001.json metadata). A chunk is written as
 * `.mp4.part` while recording and renamed to `.mp4` once finalized, which makes it eligible
 * for upload.
 */
object ChunkStore {
    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "pending").apply { mkdirs() }

    fun newSessionDir(context: Context, sessionName: String): File =
        File(root(context), sessionName).apply { mkdirs() }

    fun sessions(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    fun readyChunks(session: File): List<File> =
        session.listFiles { f -> f.name.endsWith(".mp4") }?.sortedBy { it.name } ?: emptyList()

    fun pendingCount(context: Context): Int = sessions(context).sumOf { readyChunks(it).size }

    fun partFile(session: File, index: Int, camera: String) =
        File(session, "seg_%04d_%s.mp4.part".format(index, camera))
    fun metaFile(chunk: File) = File(chunk.parentFile, chunk.name.substringBefore('.') + ".json")
    fun folderIdFile(session: File) = File(session, ".drive_folder_id")

    fun writeMeta(chunk: File, meta: JSONObject) = metaFile(chunk).writeText(meta.toString())
    fun readMeta(chunk: File): JSONObject =
        metaFile(chunk).takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()

    /** A recording session as shown on the Recordings screen. */
    class SessionSummary(
        val dir: File,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val dualCamera: Boolean,
        val rearClips: List<File>,
        val frontClips: List<File>,
        val uploadedCount: Int,
        val pendingCount: Int,
        val isRecording: Boolean,
    )

    private val CLIP = Regex("""seg_\d+(_(rear|front))?\.mp4(\.uploaded)?""")

    fun summaries(context: Context): List<SessionSummary> =
        sessions(context).mapNotNull { summary(it) }.sortedByDescending { it.startedAtMs }

    fun summary(dir: File): SessionSummary? {
        if (!dir.isDirectory) return null
        val info = File(dir, "session.json").takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val files = dir.listFiles()?.toList().orEmpty()
        val clips = files.filter { CLIP.matches(it.name) }.sortedBy { it.name }
        val uploaded = File(dir, "uploaded.log").takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0
        val started = info?.optLong("startedAtMs")?.takeIf { it > 0 } ?: dir.lastModified()
        val ended = files.maxOfOrNull { it.lastModified() } ?: started
        return SessionSummary(
            dir = dir,
            startedAtMs = started,
            endedAtMs = maxOf(started, ended),
            dualCamera = info?.optBoolean("dualCamera") ?: false,
            rearClips = clips.filter { "_front" !in it.name },
            frontClips = clips.filter { "_front" in it.name },
            uploadedCount = uploaded,
            pendingCount = readyChunks(dir).size,
            isRecording = RecordingService.currentSessionDir == dir,
        )
    }

    fun hashes(file: File): Pair<String, String> {
        val sha = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
                md5.update(buf, 0, n)
            }
        }
        return sha.digest().toHex() to md5.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

fun ChunkStore.writeSessionInfo(session: java.io.File, startedAtMs: Long, dualCamera: Boolean) {
    java.io.File(session, "session.json").writeText(
        JSONObject().put("startedAtMs", startedAtMs).put("dualCamera", dualCamera).toString()
    )
}
