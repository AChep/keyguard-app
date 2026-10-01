package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.add.AddFilePickerKind
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri

/**
 * A file the user picked or dropped on the Swift side, as the shared producers
 * expect it. Swift reports an unknown [size] as a negative number.
 */
internal fun filePickerResultOf(
    uri: String,
    name: String?,
    size: Long,
    accessToken: String? = null,
) = FilePickerResult(
    uri = leParseUri(uri),
    name = name,
    size = size.takeIf { it >= 0L },
    accessToken = accessToken,
)

/** The producer continuation that receives the picked file, or `null` on cancel. */
internal val FilePickerIntent<*>.onFilePickerResult: (FilePickerResult?) -> Unit
    get() = when (this) {
        is FilePickerIntent.OpenDocument -> onResult
        is FilePickerIntent.OpenDirectory -> onResult
        is FilePickerIntent.NewDocument -> onResult
    }

internal inline fun <R> FilePickerIntent<*>.toFilePickerRequest(
    requestId: String,
    create: (requestId: String, kind: AddFilePickerKind, mimeTypes: List<String>, suggestedName: String?) -> R,
): R = when (this) {
    is FilePickerIntent.OpenDocument -> create(requestId, AddFilePickerKind.OPEN_DOCUMENT, mimeTypes.toList(), null)
    is FilePickerIntent.OpenDirectory -> create(requestId, AddFilePickerKind.OPEN_DIRECTORY, emptyList(), null)
    is FilePickerIntent.NewDocument -> create(requestId, AddFilePickerKind.NEW_DOCUMENT, listOf(mimeType), fileName)
}
