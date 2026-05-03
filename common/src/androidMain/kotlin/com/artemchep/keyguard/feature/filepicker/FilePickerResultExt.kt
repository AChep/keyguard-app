package com.artemchep.keyguard.feature.filepicker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.artemchep.keyguard.platform.LeUriImpl

internal fun Context.toFilePickerResult(
    uri: Uri,
    size: Long? = getFileSize(uri),
): FilePickerResult = FilePickerResult(
    uri = LeUriImpl(uri),
    name = getFileName(uri),
    size = size,
)

private fun Context.getFileName(uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = contentResolver
            .query(uri, null, null, null, null)
        cursor?.use { x ->
            if (x.moveToFirst()) {
                val index = x.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = x.getString(index)
                }
            }
        }
    }
    if (result == null) {
        val r = uri.path.orEmpty()
        val cut = r.lastIndexOf('/')
        if (cut != -1) {
            result = r.substring(cut + 1)
        }
    }
    return result
}

private fun Context.getFileSize(uri: Uri): Long? {
    var result: Long? = null
    if (uri.scheme == "content") {
        val cursor = contentResolver
            .query(uri, null, null, null, null)
        cursor?.use { x ->
            if (x.moveToFirst()) {
                val index = x.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) {
                    result = x.getLong(index)
                }
            }
        }
    }
    return result
}
