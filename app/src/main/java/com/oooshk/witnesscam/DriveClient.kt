package com.oooshk.witnesscam

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthExpiredException : IOException("Drive access token rejected")

data class UploadedFile(val id: String, val sha256: String?, val md5: String?)

/** Minimal Google Drive v3 REST client: folders and resumable uploads. */
class DriveClient(private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=UTF-8".toMediaType()

    /** Finds a folder this app created with [name] under [parentId], creating it if missing. */
    fun ensureFolder(name: String, parentId: String?): String {
        val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' and trashed = false$parentClause"
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/files")
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        execute(Request.Builder().url(url).get()).use { resp ->
            val files = JSONObject(resp.body!!.string()).getJSONArray("files")
            if (files.length() > 0) return files.getJSONObject(0).getString("id")
        }

        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parentId != null) meta.put("parents", org.json.JSONArray().put(parentId))
        val create = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .post(meta.toString().toRequestBody(json))
        execute(create).use { resp -> return JSONObject(resp.body!!.string()).getString("id") }
    }

    /** Lets anyone with the link view [fileId] (used to share footage with emergency contacts). */
    fun shareWithAnyoneWithLink(fileId: String) {
        val body = JSONObject().put("role", "reader").put("type", "anyone")
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/permissions?fields=id")
            .post(body.toString().toRequestBody(json))
        execute(req).close()
    }

    /** Uploads [file] with a resumable session, returning Drive's id and server-side checksums. */
    fun upload(
        file: File,
        name: String,
        parentId: String,
        mimeType: String,
        description: String,
        appProperties: Map<String, String>,
    ): UploadedFile {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", org.json.JSONArray().put(parentId))
            .put("description", description)
            .put("appProperties", JSONObject(appProperties))

        val start = Request.Builder()
            .url("$UPLOAD_URL?uploadType=resumable&fields=id,sha256Checksum,md5Checksum")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(json))
        val sessionUrl = execute(start).use { it.header("Location") }
            ?: throw IOException("Drive did not return an upload session")

        val put = Request.Builder().url(sessionUrl).put(file.asRequestBody(mimeType.toMediaType()))
        execute(put).use { resp ->
            val body = JSONObject(resp.body!!.string())
            return UploadedFile(
                id = body.getString("id"),
                sha256 = body.optString("sha256Checksum").ifEmpty { null },
                md5 = body.optString("md5Checksum").ifEmpty { null },
            )
        }
    }

    private fun execute(builder: Request.Builder): Response {
        val resp = http.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
        if (resp.code == 401) {
            resp.close()
            throw AuthExpiredException()
        }
        if (!resp.isSuccessful) {
            val msg = resp.body?.string()?.take(500)
            resp.close()
            throw IOException("Drive HTTP ${resp.code}: $msg")
        }
        return resp
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    }
}
