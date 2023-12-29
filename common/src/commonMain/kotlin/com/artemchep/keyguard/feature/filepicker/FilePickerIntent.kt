package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.platform.LeUri

sealed interface FilePickerIntent<R> {
    val onResult: (R) -> Unit

    class OpenDocument(
        /** The mime types to filter by, e.g. image/\*. */
        val mimeTypes: Array<String> = mimeTypesAll,
        override val onResult: (Ifo?) -> Unit,
    ) : FilePickerIntent<OpenDocument.Ifo?> {
        companion object {
            val mimeTypesAll = arrayOf("*/*")
        }

        data class Ifo(
            val uri: LeUri,
            val name: String?,
            val size: Long?,
        )
    }
}
