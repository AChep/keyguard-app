package com.artemchep.keyguard.common.service.imports.impl.chromium

import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginColumns
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginImporter
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV

/**
 * Chrome and the browsers built on Chromium:
 * `name,url,username,password,note`. Older versions have no `note` column.
 */
internal class ChromiumCsvImporter : CsvLoginImporter(
    format = ImportFormat(
        id = ImportFormatId.Chromium,
        title = "Chrome",
        aliases = listOf("Edge", "Brave", "Opera", "Vivaldi", "Arc"),
        extensions = listOf(IMPORT_EXTENSION_CSV),
    ),
    requiredColumns = setOf("url", "username", "password"),
    columns = CsvLoginColumns(
        title = "name",
        url = "url",
        username = "username",
        password = "password",
        note = "note",
    ),
)
