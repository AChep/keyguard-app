package com.artemchep.keyguard.common.service.imports.impl.bitwarden

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.CsvImporter
import com.artemchep.keyguard.common.service.imports.impl.CsvRow
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlin.time.Instant

/**
 * Bitwarden's CSV export: `folder,favorite,type,name,notes,fields,reprompt,
 * archivedDate,login_uri,login_username,login_password,login_totp`. An
 * organization export has a `collections` column instead of `folder`, and
 * exports older than item archiving have no `archivedDate`. The format
 * carries only logins and notes. Not registered on its own:
 * [BitwardenImporter] reads through it and passes in its [format].
 */
internal class BitwardenCsvImporter(
    override val format: ImportFormat,
) : CsvImporter() {
    companion object {
        private const val TYPE_LOGIN = "login"
        private const val TYPE_NOTE = "note"

        private const val FLAG_TRUE = "1"

        private const val FOLDER_DELIMITER = '/'
        private const val FIELD_SEPARATOR = ": "
    }

    override val requiredColumns = setOf("type", "name", "login_username", "login_password")

    override fun titleOf(row: CsvRow): String? = row["name"].orNullIfBlank()

    override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val title = titleOf(row)
        val folderPath = row["folder"]
            ?: row["collections"]?.substringBefore(',')
        val folderKey = folderPath
            ?.split(FOLDER_DELIMITER)
            ?.let(plan::folder)
        val favorite = row["favorite"]?.trim() == FLAG_TRUE
        val reprompt = row["reprompt"]?.trim() == FLAG_TRUE
        // Any date means archived; the vault stamps its own when it archives.
        val archived = row["archivedDate"].orNullIfBlank() != null
        val fields = row["fields"]
            ?.lineSequence()
            ?.mapNotNull { line ->
                val name = line.substringBefore(FIELD_SEPARATOR)
                val value = line.substringAfter(FIELD_SEPARATOR, missingDelimiterValue = "")
                importedFieldOrNull(name, value)
            }
            ?.toList()
            .orEmpty()
        val meta = ImportedItemMeta(
            now = now,
            title = title,
            note = row["notes"],
            favorite = favorite,
            reprompt = reprompt,
            folderKey = folderKey,
            archived = archived,
        )
        when (row["type"]?.trim()?.lowercase()) {
            TYPE_LOGIN -> plan.login(
                meta = meta.copy(
                    uris = row["login_uri"]
                        ?.split(',')
                        ?.mapNotNull(::importedUriOrNull)
                        .orEmpty(),
                ),
                username = row["login_username"],
                password = row["login_password"],
                totp = row["login_totp"],
                fields = fields,
            )

            TYPE_NOTE -> plan.item(meta, DSecret.Type.SecureNote, fields)

            else -> plan.skip(ImportSkipReason.Item, title)
        }
    }
}
