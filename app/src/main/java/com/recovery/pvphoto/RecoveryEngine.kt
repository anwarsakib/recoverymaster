package com.recovery.pvphoto

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Puts deleted files back where apps can see them, without root:
 *
 * - Items living in the MediaStore trash (Android 11+) are restored with
 *   MediaStore.createTrashRequest(uri, false): the system shows a confirm
 *   dialog and moves the item back to the gallery.
 * - Items found on disk are copied into fresh "Recovered*" folders
 *   (Pictures/RecoveredPhotos, Movies/RecoveredVideos, Documents/RecoveredFiles)
 *   and indexed into MediaStore so the gallery picks them up immediately.
 */
object RecoveryEngine {

    /** System restore request for one trashed item (Android 11+ only). */
    fun buildRestoreIntent(context: Context, item: RecoverItem): PendingIntent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val uri = item.uri ?: return null
        return try {
            MediaStore.createTrashRequest(context.contentResolver, listOf(uri), false)
        } catch (t: Throwable) {
            null
        }
    }

    fun recoverFile(context: Context, item: RecoverItem, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val where: String
                if (Build.VERSION.SDK_INT >= 29) {
                    where = insertViaMediaStore(context, item)
                } else {
                    where = copyToPublicDir(context, item)
                }
                onResult(true, where)
            } catch (t: Throwable) {
                onResult(false, t.message ?: t.javaClass.simpleName)
            }
        }.start()
    }

    private fun insertViaMediaStore(context: Context, item: RecoverItem): String {
        val (collection, relPath) = when (item.kind) {
            Kind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                (Environment.DIRECTORY_PICTURES + "/RecoveredPhotos")
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                (Environment.DIRECTORY_MOVIES + "/RecoveredVideos")
            Kind.DOC -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                (Environment.DIRECTORY_DOCUMENTS + "/RecoveredFiles")
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outUri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(outUri)?.use { os -> copySource(context, item, os) }
                ?: throw IOException("cannot open output stream")
        } catch (t: Throwable) {
            resolver.delete(outUri, null, null)
            throw t
        }
        resolver.update(outUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return relPath
    }

    private fun copyToPublicDir(context: Context, item: RecoverItem): String {
        val base = when (item.kind) {
            Kind.PHOTO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Kind.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            Kind.DOC -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        }
        val sub = when (item.kind) {
            Kind.PHOTO -> "RecoveredPhotos"
            Kind.VIDEO -> "RecoveredVideos"
            Kind.DOC -> "RecoveredFiles"
        }
        val dir = File(base, sub)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
        val target = uniqueTarget(dir, item.name)
        FileOutputStream(target).use { os -> copySource(context, item, os) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(item.mime), null)
        return target.absolutePath
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            candidate = File(dir, "$base($i)." + ext.ifEmpty { "dat" })
            i++
        }
        return candidate
    }

    private fun copySource(context: Context, item: RecoverItem, os: OutputStream) {
        val ins: InputStream = when {
            item.uri != null -> context.contentResolver.openInputStream(item.uri)
                ?: throw IOException("cannot open source (permission missing?)")
            item.file != null -> FileInputStream(item.file)
            else -> throw IOException("no readable source")
        }
        ins.use { it.copyTo(os) }
    }
}
