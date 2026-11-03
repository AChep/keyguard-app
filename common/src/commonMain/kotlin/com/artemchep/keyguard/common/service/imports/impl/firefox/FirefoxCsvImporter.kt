package com.artemchep.keyguard.common.service.imports.impl.firefox

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
 * Firefox: `url,username,password,httpRealm,formActionOrigin,guid,
 * timeCreated,timeLastUsed,timePasswordChanged`. The export has no titles, so
 * an item is named after its host.
 */
internal class FirefoxCsvImporter : CsvImporter() {
    companion object {
        /**
         * Firefox keeps its own internal logins, such as the Firefox account,
         * under `chrome://` addresses. They are browser state, not user
         * credentials.
         */
        private const val INTERNAL_URL_PREFIX = "chrome://"
    }

    override val format = ImportFormat(
        id = ImportFormatId.Firefox,
        title = "Firefox",
        extensions = listOf(IMPORT_EXTENSION_CSV),
    )

    override val requiredColumns = setOf("url", "username", "password")

    override fun titleOf(row: CsvRow): String? = row["url"]
        ?.let { url ->
            importedTitleFromUriOrNull(url)
                ?: url.orNullIfBlank()
        }

    override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val url = row["url"]
        if (url != null && url.startsWith(INTERNAL_URL_PREFIX, ignoreCase = true)) {
            return
        }
        // The form may post to a different origin than the page it is on;
        // both are where the login is used.
        val uris = listOfNotNull(
            importedUriOrNull(url),
            importedUriOrNull(row["formActionOrigin"]),
        ).distinctBy { it.uri.trimEnd('/').lowercase() }
        plan.login(
            meta = ImportedItemMeta(
                now = now,
                title = titleOf(row),
                uris = uris,
            ),
            username = row["username"],
            password = row["password"],
        )
    }
}
