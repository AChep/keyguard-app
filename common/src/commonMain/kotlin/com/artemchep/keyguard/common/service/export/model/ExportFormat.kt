package com.artemchep.keyguard.common.service.export.model

enum class ExportFormat(
    val extension: String,
) {
    /**
     * An encrypted ZIP archive with the vault in an
     * extended Bitwarden JSON export format.
     */
    Zip(
        extension = "zip",
    ),

    /**
     * A KeePass database.
     */
    Kdbx(
        extension = "kdbx",
    ),
}
