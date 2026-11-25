package com.recovery.pvphoto

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File

/**
 * Finds deleted/recoverable files WITHOUT root, using every public API available:
 *
 * 1. MediaStore trash (Android 11+): items trashed via the system trash API
 *    (Google Photos, Files app, gallery apps) have IS_TRASHED = 1 and can be
 *    restored to the gallery with the system confirm dialog.
 * 2. ".trashed-<timestamp>-<name>" files left on shared storage by the system
 *    trash mechanism, plus unindexed leftovers: files that still exist on disk
 *    but are missing from the gallery index (removed records, hidden folders,
 *    old .thumbnails caches, SD card leftovers).
 *
 * Files currently present in the gallery index are EXCLUDED, so the app shows
 * only "deleted" candidates.
 */
object Scanner {

    const val MAX_ITEMS = 5000
    const val TRASH_PREFIX = ".trashed-"
    const val SOURCE_TRASH = "trash"
    const val SOURCE_DISK = "disk"

    private val photoExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng")
    private val videoExt = setOf("mp4", "3gp", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg")
    private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "zip", "rar", "odt", "ods")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun mimeFor(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "m4v" -> "video/x-m4v"
        "ts", "mpg", "mpeg" -> "video/mpeg"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        else -> "application/octet-stream"
    }

    /** ".trashed-1730000000-name.jpg" -> "name.jpg" */
    fun stripTrashName(n: String): String {
        if (!n.startsWith(TRASH_PREFIX)) return n
        val rest = n.drop(TRASH_PREFIX.length).dropWhile { it.isDigit() || it == '-' }
        return rest.ifEmpty { n }
    }

    fun scan(context: Context, kind: Kind): List<RecoverItem> {
        val out = ArrayList<RecoverItem>()

        if (Build.VERSION.SDK_INT >= 30) {
            out.addAll(queryTrashed(context, kind))
        }

        if (PermissionUtils.hasDeepScanAccess(context)) {
            val indexed = loadIndexed(context)
            for (root in storageRoots(context)) {
                deepWalk(File(root), kind, indexed, out, SystemClock.elapsedRealtime())
            }
        }

        // Deduplicate and sort newest first, cap the list size.
        val seen = HashSet<String>()
        val res = ArrayList<RecoverItem>()
        for (item in out.sortedByDescending { it.dateModified }) {
            val key = item.path?.lowercase() ?: (item.uri?.toString() ?: item.name)
            if (seen.add(key)) res.add(item)
            if (res.size >= MAX_ITEMS) break
        }
        return res
    }

    // ---------------------------------------------------------------- trash

    private fun queryTrashed(context: Context, kind: Kind): List<RecoverItem> {
        val list = ArrayList<RecoverItem>()
        val collection: Uri = when (kind) {
            Kind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Kind.DOC -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        try {
            context.contentResolver.query(collection, projection, "is_trashed=1", null, "date_modified DESC")
                ?.use { c ->
                    val idxId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val idxName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val idxData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val idxSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val idxDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val idxMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    while (c.moveToNext()) {
                        val name = c.getString(idxName) ?: continue
                        if (kind == Kind.DOC && extOf(name) !in docExt) continue
                        val data = if (idxData >= 0) c.getString(idxData) else null
                        list.add(
                            RecoverItem(
                                uri = ContentUris.withAppendedId(collection, c.getLong(idxId)),
                                file = data?.let { File(it) },
                                name = name,
                                path = data,
                                size = if (idxSize >= 0) c.getLong(idxSize) else 0L,
                                dateModified = if (idxDate >= 0) c.getLong(idxDate) else 0L,
                                mime = c.getString(idxMime) ?: mimeFor(extOf(name)),
                                kind = kind,
                                source = SOURCE_TRASH
                            )
                        )
                    }
                }
        } catch (t: Throwable) {
            // Some devices/emulators do not support the trash column; deep scan still works.
        }
        return list
    }

    // -------------------------------------------------------------- indexed

    private class Indexed {
        val paths = HashSet<String>()
        val nameSizes = HashSet<String>()
    }

    /** Every file the gallery currently shows; these are excluded from results. */
    private fun loadIndexed(context: Context): Indexed {
        val indexed = Indexed()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val selection = if (Build.VERSION.SDK_INT >= 30) "is_trashed=0" else null
        for (uri in collections) {
            try {
                context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
                    val idxData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val idxName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val idxSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val name = c.getString(idxName) ?: continue
                        val size = if (idxSize >= 0) c.getLong(idxSize) else 0L
                        if (idxData >= 0) {
                            val data = c.getString(idxData)
                            if (!data.isNullOrEmpty()) indexed.paths.add(data.lowercase())
                        }
                        indexed.nameSizes.add("${name.lowercase()}:$size")
                    }
                }
            } catch (t: Throwable) {
                // Ignore and continue with the other collections.
            }
        }
        return indexed
    }

    // ------------------------------------------------------------ deep scan

    private fun storageRoots(context: Context): List<String> {
        val roots = ArrayList<String>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null) roots.add(primary.absolutePath)
        try {
            // Derive every mounted volume root (e.g. SD card) from app-specific dirs.
            for (f in context.getExternalFilesDirs(null)) {
                if (f == null) continue
                val p = f.absolutePath
                val i = p.indexOf("/Android/data")
                if (i > 0) {
                    val root = p.substring(0, i)
                    if (!roots.contains(root)) roots.add(root)
                }
            }
        } catch (t: Throwable) {
            // Keep just the primary root.
        }
        return roots
    }

    private const val TIME_BUDGET_MS = 90_000L

    /** @return false when the walk must stop (time/size budget reached). */
    private fun deepWalk(
        dir: File,
        kind: Kind,
        indexed: Indexed,
        out: MutableList<RecoverItem>,
        startedAt: Long
    ): Boolean {
        if (SystemClock.elapsedRealtime() - startedAt > TIME_BUDGET_MS) return false
        if (out.size >= MAX_ITEMS) return false
        val files = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return true

        for (f in files) {
            val n = f.name
            if (f.isDirectory) {
                // App-private system area is useless to us (and unreadable anyway).
                if (n == "Android") continue
                if (n == "RecoveredPhotos" || n == "RecoveredVideos" || n == "RecoveredFiles") continue
                if (!deepWalk(f, kind, indexed, out, startedAt)) return false
            } else {
                if (out.size >= MAX_ITEMS) return false
                if (f.length() <= 0) continue
                val ln = n.lowercase()
                val trashedName = ln.startsWith(TRASH_PREFIX)
                val ext = extOf(if (trashedName) stripTrashName(n) else n)
                val wanted = when (kind) {
                    Kind.PHOTO -> ext in photoExt
                    Kind.VIDEO -> ext in videoExt
                    Kind.DOC -> ext in docExt
                }
                if (!wanted) continue
                if (!trashedName) {
                    // Skip anything the gallery already shows right now.
                    val p = f.absolutePath.lowercase()
                    if (indexed.paths.contains(p)) continue
                    if (indexed.nameSizes.contains("$ln:${f.length()}")) continue
                }
                out.add(
                    RecoverItem(
                        uri = null,
                        file = f,
                        name = stripTrashName(n),
                        path = f.absolutePath,
                        size = f.length(),
                        dateModified = f.lastModified() / 1000,
                        mime = mimeFor(ext),
                        kind = kind,
                        source = SOURCE_DISK
                    )
                )
            }
        }
        return true
    }
}
