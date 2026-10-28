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

    fun partFile(session: File, index: Int) = File(session, "seg_%04d.mp4.part".format(index))
    fun metaFile(chunk: File) = File(chunk.parentFile, chunk.name.substringBefore('.') + ".json")
    fun folderIdFile(session: File) = File(session, ".drive_folder_id")

    fun writeMeta(chunk: File, meta: JSONObject) = metaFile(chunk).writeText(meta.toString())
    fun readMeta(chunk: File): JSONObject =
        metaFile(chunk).takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()

    /** Removes the session directory once every chunk has been uploaded and nothing is recording. */
    fun pruneIfDone(session: File) {
        val left = session.listFiles { f -> f.name.endsWith(".mp4") || f.name.endsWith(".part") || f.name.endsWith(".uploaded") }
        if (left.isNullOrEmpty() && RecordingService.currentSessionDir != session) session.deleteRecursively()
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
