package com.rawr.camera.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri

/** Provider operations for pending MediaStore videos and user-selected document trees. */
internal class VideoOutputStore(private val context: Context) {
    fun create(saveLocationId: String, displayName: String, capturedAt: Long, mimeType: String = "video/mp4"): Uri {
        val destination = CaptureSaveLocation.fromId(saveLocationId, CaptureSaveLocation.VIDEO_ROOTS)
        val resolver = context.contentResolver
        val tree = destination.treeUri?.toUri()
        if (destination.relativePath.isNotEmpty()) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, destination.relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DATE_TAKEN, capturedAt)
            }
            // A picked folder's volume may be unmounted; its tree grant is the fallback.
            val inserted = if (tree == null) resolver.insert(destination.videoCollection(), values)
                else runCatching { resolver.insert(destination.videoCollection(), values) }.getOrNull()
            if (inserted != null || tree == null) return checkNotNull(inserted) {
                "Could not create video in ${destination.relativePath}"
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return checkNotNull(DocumentsContract.createDocument(resolver, parent, mimeType, displayName)) {
            "Could not create video in the selected folder"
        }
    }

    fun publish(uri: Uri) {
        // Document providers have no MediaStore pending flag. Closing the
        // native descriptor finalizes their file at the selected destination.
        if (uri.authority == MediaStore.AUTHORITY) {
            check(context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                "Video output disappeared before publication"
            }
        }
    }

    /**
     * Saves a sidecar file of a published video (a Gyroflow .gcsv log). In a folder picked as a document tree it
     * goes beside the video, where Gyroflow finds it on its own; MediaStore keeps camera folders for media, so
     * there it goes to Download/RAWR/Gyroflow. Returns where it went.
     */
    fun saveSidecar(videoUri: Uri, saveLocationId: String, displayName: String, source: java.io.File): Uri {
        val resolver = context.contentResolver
        val target: Uri = if (videoUri.authority == MediaStore.AUTHORITY) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, SIDECAR_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/RAWR/Gyroflow")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "Could not create $displayName in Downloads"
            }
        } else {
            val tree = checkNotNull(CaptureSaveLocation.fromId(saveLocationId, CaptureSaveLocation.VIDEO_ROOTS).treeUri) {
                "Video folder has no document tree"
            }.toUri()
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            checkNotNull(DocumentsContract.createDocument(resolver, parent, SIDECAR_MIME, displayName)) {
                "Could not create $displayName beside the video"
            }
        }
        try {
            checkNotNull(resolver.openOutputStream(target)) { "Could not open $displayName" }.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            if (target.authority == MediaStore.AUTHORITY) {
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Exception) {
            runCatching { delete(target) }
            throw error
        }
        return target
    }

    fun delete(uri: Uri) {
        if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
        else check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            "Could not remove unfinished video"
        }
    }

    private companion object {
        /** A generic type keeps the .gcsv name: providers rename files whose extension does not match the type. */
        const val SIDECAR_MIME = "application/octet-stream"
    }
}
