package com.artemchep.keyguard.common.service.imports.impl.safari

import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginColumns
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginImporter
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV

/**
 * Safari and the Passwords app: `Title,URL,Username,Password,Notes,OTPAuth`.
 */
internal class SafariCsvImporter : CsvLoginImporter(
    format = ImportFormat(
        id = ImportFormatId.Safari,
        title = "Safari",
        aliases = listOf("Apple Passwords"),
        extensions = listOf(IMPORT_EXTENSION_CSV),
    ),
    requiredColumns = setOf("url", "username", "password"),
    columns = CsvLoginColumns(
        title = "title",
        url = "url",
        username = "username",
        password = "password",
        note = "notes",
        totp = "otpauth",
    ),
)
