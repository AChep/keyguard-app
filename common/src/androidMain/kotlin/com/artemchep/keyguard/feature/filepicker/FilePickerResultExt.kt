package com.artemchep.keyguard.feature.filepicker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

internal fun Context.toDirectoryPickerResult(
    uri: Uri,
): FilePickerResult = FilePickerResult(
    uri = LeUriImpl(uri),
    name = getDirectoryName(uri),
    size = null,
)

private fun Context.getDirectoryName(uri: Uri): String? {
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()
    val documentName = documentId
        ?.let { id ->
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
            queryDocumentName(documentUri)
        }
    return documentName ?: documentId?.toDisplayName()
}

private fun Context.queryDocumentName(uri: Uri): String? = runCatching {
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    contentResolver
        .query(uri, projection, null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index >= 0) {
                cursor.getString(index)
            } else {
                null
            }
        }
}.getOrNull()

private fun String.toDisplayName(): String? = substringAfterLast('/')
    .substringAfterLast(':')
    .takeIf { it.isNotBlank() }
    ?: takeIf { it.isNotBlank() }

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
