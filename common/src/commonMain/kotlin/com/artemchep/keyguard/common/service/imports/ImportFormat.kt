package com.artemchep.keyguard.common.service.imports

/**
 * Every file format Keyguard can import. Declaration order is user-visible:
 * it is the order the import screen lists the formats in.
 */
enum class ImportFormatId {
    Chromium,
    Firefox,
    Safari,
    OnePassword,
    Bitwarden,
    Dashlane,
    Enpass,
    KeePassXc,
    Keeper,
    KeyguardZip,
    LastPass,
    NordPass,
    ProtonPass,
}

/**
 * A file format an [Importer] reads, as the import screen presents it.
 */
data class ImportFormat(
    val id: ImportFormatId,
    /**
     * The product name, shown verbatim; brand names are not translated.
     */
    val title: String,
    /**
     * Other products that export the very same file, e.g. Edge for the
     * Chromium format.
     */
    val aliases: List<String> = emptyList(),
    /**
     * The file extensions of an export, without the dot. Display-only: the
     * file picker accepts any file, because the platforms do not agree on a
     * MIME type for CSV, and the content is validated instead.
     */
    val extensions: List<String>,
)
