package com.recovery.pvphoto

import android.net.Uri
import android.os.Bundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Kind { PHOTO, VIDEO, DOC }

data class RecoverItem(
    val uri: Uri?,
    val file: File?,
    val name: String,
    val path: String?,
    val size: Long,
    val dateModified: Long, // seconds since epoch
    val mime: String,
    val kind: Kind,
    val source: String // "trash" | "disk"
)

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

fun formatDate(seconds: Long): String = try {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale("bn")).format(Date(seconds * 1000))
} catch (t: Throwable) {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(seconds * 1000))
}

fun RecoverItem.toBundle(): Bundle = Bundle().apply {
    putString("k", kind.name)
    putString("n", name)
    putString("p", path)
    putLong("s", size)
    putLong("d", dateModified)
    putString("m", mime)
    putString("src", source)
    putString("u", uri?.toString())
    putBoolean("hasFile", file != null)
}

object BundleKeys {
    const val KIND = "k"
    const val NAME = "n"
    const val PATH = "p"
    const val SIZE = "s"
    const val DATE = "d"
    const val MIME = "m"
    const val SOURCE = "src"
    const val URI = "u"
    const val HAS_FILE = "hasFile"
}

fun recoverItemFromBundle(b: Bundle): RecoverItem {
    val path = b.getString(BundleKeys.PATH)
    return RecoverItem(
        uri = b.getString(BundleKeys.URI)?.let { Uri.parse(it) },
        file = if (b.getBoolean(BundleKeys.HAS_FILE) && path != null) File(path) else null,
        name = b.getString(BundleKeys.NAME) ?: "unknown",
        path = path,
        size = b.getLong(BundleKeys.SIZE),
        dateModified = b.getLong(BundleKeys.DATE),
        mime = b.getString(BundleKeys.MIME) ?: "application/octet-stream",
        kind = Kind.valueOf(b.getString(BundleKeys.KIND) ?: "PHOTO"),
        source = b.getString(BundleKeys.SOURCE) ?: "disk"
    )
}
