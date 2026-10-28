package com.oooshk.witnesscam

import android.content.Context
import java.io.File

/**
 * Resolves Drive folder ids for a recording session. Serialized so the uploader and the
 * share-link sender never create the same folder twice.
 */
object DriveFolders {
    private val lock = Any()

    fun sessionFolder(context: Context, drive: DriveClient, session: File): String = synchronized(lock) {
        val prefs = Prefs(context)
        val rootId = prefs.driveRootFolderId ?: drive.ensureFolder(UploadWorker.ROOT_FOLDER, null).also {
            prefs.driveRootFolderId = it
        }
        val idFile = ChunkStore.folderIdFile(session)
        idFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: drive.ensureFolder(session.name, rootId).also { idFile.writeText(it) }
    }

    fun forgetCachedIds(context: Context) = synchronized(lock) {
        Prefs(context).driveRootFolderId = null
        ChunkStore.sessions(context).forEach { ChunkStore.folderIdFile(it).delete() }
    }

    fun folderLink(folderId: String) = "https://drive.google.com/drive/folders/$folderId"
}
