package com.artemchep.keyguard.common.service.imports.impl.lastpass

import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.impl.CsvImporter
import com.artemchep.keyguard.common.service.imports.impl.CsvRow
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedTitleFromUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlin.time.Instant

/**
 * LastPass: `url,username,password,totp,extra,name,grouping,fav`. The Safari
 * extension's export has no `totp` column.
 *
 * A secure note has the address `http://sn`; a structured one, such as a
 * card, starts its `extra` with a `NoteType:` line followed by `Key:Value`
 * lines, see [mapLastPassNote]. A row with the address `http://group` only
 * stands for its folder.
 */
internal class LastPassCsvImporter(
    private val sshKeyImportService: SshKeyImportService,
) : CsvImporter() {
    companion object {
        private const val SECURE_NOTE_URL = "http://sn"
        private const val FOLDER_URL = "http://group"
        private const val NO_FOLDER = "(none)"

        private const val FLAG_TRUE = "1"
        private const val GROUP_DELIMITER = '\\'
    }

    override val format = ImportFormat(
        id = ImportFormatId.LastPass,
        title = "LastPass",
        extensions = listOf(IMPORT_EXTENSION_CSV),
    )

    override val requiredColumns = setOf("url", "username", "password", "extra", "name", "grouping")

    override fun titleOf(row: CsvRow): String? = row["name"]
        ?.let(::decodeHtmlEntities)
        .orNullIfBlank()
        ?: importedTitleFromUriOrNull(row["url"])

    override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val title = titleOf(row)
        val folderKey = row["grouping"]
            ?.let(::decodeHtmlEntities)
            ?.takeUnless { it.trim() == NO_FOLDER }
            ?.split(GROUP_DELIMITER)
            ?.let(plan::folder)
        val meta = ImportedItemMeta(
            now = now,
            title = title,
            note = row["extra"],
            favorite = row["fav"]?.trim() == FLAG_TRUE,
            folderKey = folderKey,
        )
        when (row["url"]?.trim()) {
            // The folder is planned above; the row holds nothing else.
            FOLDER_URL -> Unit

            SECURE_NOTE_URL -> mapLastPassNote(
                meta = meta,
                plan = plan,
                sshKeyImportService = sshKeyImportService,
            )

            else -> plan.login(
                meta = meta.copy(
                    uris = listOfNotNull(importedUriOrNull(row["url"])),
                ),
                username = row["username"],
                password = row["password"],
                totp = row["totp"],
            )
        }
    }
}

private val numericEntity = Regex("&#(\\d{1,7});")
private val hexEntity = Regex("&#[xX]([0-9a-fA-F]{1,6});")

/**
 * LastPass escapes some characters of names and groups as HTML entities.
 * Only labels are decoded: a password may legitimately contain `&amp;`.
 */
internal fun decodeHtmlEntities(
    text: String,
): String = text
    .replace(numericEntity) { match ->
        match.groupValues[1].toIntOrNull()?.toCodePointString() ?: match.value
    }
    .replace(hexEntity) { match ->
        match.groupValues[1].toIntOrNull(radix = 16)?.toCodePointString() ?: match.value
    }
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF

private fun Int.toCodePointString(): String? = when {
    this < 0 || this > MAX_CODE_POINT -> null
    this < SUPPLEMENTARY_START -> toChar().toString()
    else -> {
        val offset = this - SUPPLEMENTARY_START
        charArrayOf(
            (HIGH_SURROGATE_START + (offset shr SURROGATE_BITS)).toChar(),
            (LOW_SURROGATE_START + (offset and SURROGATE_MASK)).toChar(),
        ).concatToString()
    }
}
