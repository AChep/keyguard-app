package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.impl.CsvImporter
import com.artemchep.keyguard.common.service.imports.impl.CsvRow
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlin.time.Instant

/**
 * Proton Pass's CSV: `type,name,url,email,username,password,note,totp,…,vault`.
 * It carries logins in full and every other item type only as its note. Not
 * registered on its own: [ProtonPassImporter] reads through it and passes in
 * its format.
 */
internal class ProtonPassCsvImporter(
    override val format: ImportFormat,
) : CsvImporter() {
    companion object {
        private const val TYPE_LOGIN = "login"
        private const val TYPE_ALIAS = "alias"
        private const val URL_SEPARATOR = ", "
    }

    override val requiredColumns = setOf("type", "name", "url", "username", "password", "note")

    override fun titleOf(row: CsvRow): String? = row["name"].orNullIfBlank()

    override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val meta = ImportedItemMeta(
            now = now,
            title = titleOf(row),
            note = row["note"],
            folderKey = row["vault"]?.let { plan.folder(listOf(it)) },
        )
        when (row["type"]?.trim()) {
            TYPE_LOGIN, TYPE_ALIAS -> {
                val username = protonPassUsername(
                    username = row["username"].orNullIfBlank(),
                    email = row["email"].orNullIfBlank(),
                )
                plan.login(
                    meta = meta.copy(
                        uris = row["url"]
                            ?.split(URL_SEPARATOR)
                            ?.mapNotNull(::importedUriOrNull)
                            .orEmpty(),
                    ),
                    username = username.username,
                    password = row["password"],
                    totp = row["totp"],
                    fields = username.fields,
                )
            }

            // The CSV carries other item types only as their note, with the
            // content written into it; the note keeps all of it.
            else -> plan.item(meta, DSecret.Type.SecureNote)
        }
    }
}
