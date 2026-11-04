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

    /** An ordinary ZIP protected by age password encryption. */
    ZipAge(
        extension = "zip.age",
    ),

    /**
     * A KeePass database.
     */
    Kdbx(
        extension = "kdbx",
    ),
}
