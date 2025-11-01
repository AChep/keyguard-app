package com.artemchep.keyguard.feature.filepicker

sealed interface FilePickerIntent<R> {
    companion object {
        const val MIME_TYPE_ALL = "*/*"

        val mimeTypesAll = arrayOf(MIME_TYPE_ALL)

        val mimeTypesKeePass
            get() = arrayOf(
                "application/x-kdbx",
                "application/x-keepass",
            )
    }

    val onResult: (R) -> Unit

    class NewDocument(
        val fileName: String,
        /** The mime types to filter by, e.g. image/\*. */
        val mimeType: String = MIME_TYPE_ALL,
        val readUriPermission: Boolean = true,
        val writeUriPermission: Boolean = false,
        val persistableUriPermission: Boolean = false,
        override val onResult: (FilePickerResult?) -> Unit,
    ) : FilePickerIntent<FilePickerResult?>

    class OpenDocument(
        /** The mime types to filter by, e.g. image/\*. */
        val mimeTypes: Array<String> = mimeTypesAll,
        val readUriPermission: Boolean = true,
        val writeUriPermission: Boolean = false,
        val persistableUriPermission: Boolean = false,
        override val onResult: (FilePickerResult?) -> Unit,
    ) : FilePickerIntent<FilePickerResult?>
}
