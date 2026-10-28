package com.oooshk.witnesscam

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Uploads chunks to the app's own folder in Dropbox (Dropbox/Apps/<app name>/<session>/).
 * Each video is followed by a small .json sidecar holding its SHA-256, start time and location.
 */
class DropboxUploader(private val context: Context) : CloudUploader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val octet = "application/octet-stream".toMediaType()
    private val json = "application/json".toMediaType()

    override suspend fun upload(chunk: ChunkUpload): String {
        val folder = "/" + chunk.session.name
        val path = "$folder/${chunk.file.name}"
        val meta = upload(path, chunk.file.asRequestBody(octet))

        // Dropbox's content_hash is its own block-wise SHA-256; recompute it locally to verify.
        val remoteHash = meta.optString("content_hash")
        if (remoteHash.isNotEmpty() && !remoteHash.equals(contentHash(chunk.file), ignoreCase = true)) {
            throw IOException("Checksum mismatch for ${chunk.file.name}")
        }

        val sidecar = JSONObject(chunk.properties)
            .put("file", chunk.file.name)
            .put("md5", chunk.md5)
            .put("description", chunk.description)
        // Bytes, not a String: OkHttp appends "; charset=utf-8" to String bodies, which Dropbox rejects.
        upload(path.removeSuffix(".mp4") + ".json", sidecar.toString(2).toByteArray().toRequestBody(octet))
        return meta.optString("path_display", path)
    }

    override suspend fun shareSessionLink(session: File): String {
        val path = "/" + session.name
        // The folder may not exist yet if no chunk has finished uploading.
        rpc("files/create_folder_v2", JSONObject().put("path", path).put("autorename", false), allowConflict = true)

        val created = rpc("sharing/create_shared_link_with_settings", JSONObject().put("path", path), allowConflict = true)
        created.optString("url").takeIf { it.isNotEmpty() }?.let { return it }

        // A link already exists for this folder: look it up.
        val existing = rpc("sharing/list_shared_links", JSONObject().put("path", path).put("direct_only", true))
        val links = existing.optJSONArray("links")
        if (links != null && links.length() > 0) return links.getJSONObject(0).getString("url")
        throw IOException("Dropbox did not return a shared link")
    }

    private fun upload(path: String, body: RequestBody): JSONObject {
        val arg = JSONObject()
            .put("path", path)
            .put("mode", "add")
            .put("autorename", true)
            .put("mute", true)
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/upload")
            .header("Dropbox-API-Arg", arg.toString())
            .post(body)
        return execute(req, allowConflict = false)
    }

    private fun rpc(endpoint: String, args: JSONObject, allowConflict: Boolean = false): JSONObject {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/$endpoint")
            .post(args.toString().toByteArray().toRequestBody(json))
        return execute(req, allowConflict)
    }

    /** Runs [builder] with a valid token, refreshing once on 401. A 409 returns {} when allowed. */
    private fun execute(builder: Request.Builder, allowConflict: Boolean): JSONObject {
        for (attempt in 0..1) {
            val token = DropboxAuth.accessToken(context, forceRefresh = attempt > 0)
            http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return if (body.isBlank()) JSONObject() else JSONObject(body)
                    resp.code == 401 && attempt == 0 -> Unit // token expired early; refresh and retry
                    resp.code == 401 -> throw CloudAuthRequiredException("Dropbox rejected the access token")
                    resp.code == 409 && allowConflict -> return JSONObject()
                    else -> throw IOException("Dropbox HTTP ${resp.code}: ${body.take(300)}")
                }
            }
        }
        throw IOException("unreachable")
    }

    companion object {
        private const val BLOCK = 4 * 1024 * 1024

        /** Dropbox content hash: SHA-256 over the concatenated SHA-256 of each 4 MB block. */
        fun contentHash(file: File): String {
            val overall = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BLOCK)
                while (true) {
                    var filled = 0
                    while (filled < BLOCK) {
                        val n = input.read(buf, filled, BLOCK - filled)
                        if (n < 0) break
                        filled += n
                    }
                    if (filled == 0) break
                    overall.update(MessageDigest.getInstance("SHA-256").apply { update(buf, 0, filled) }.digest())
                    if (filled < BLOCK) break
                }
            }
            return overall.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
