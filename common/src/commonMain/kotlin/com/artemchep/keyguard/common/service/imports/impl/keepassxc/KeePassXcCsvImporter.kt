package com.artemchep.keyguard.common.service.imports.impl.keepassxc

import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.impl.CsvFolderColumn
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginColumns
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginImporter
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV

/**
 * KeePassXC: `Group,Title,Username,Password,URL,Notes,TOTP,Icon,
 * Last Modified,Created`. The group is a `/`-separated path that starts with
 * the name of the database's root group.
 */
internal class KeePassXcCsvImporter : CsvLoginImporter(
    format = ImportFormat(
        id = ImportFormatId.KeePassXc,
        title = "KeePassXC",
        extensions = listOf(IMPORT_EXTENSION_CSV),
    ),
    requiredColumns = setOf("group", "title", "username", "password", "url"),
    columns = CsvLoginColumns(
        title = "title",
        titleFromUrl = false,
        url = "url",
        username = "username",
        password = "password",
        note = "notes",
        totp = "totp",
        // The root group is the database itself, not a folder in it.
        folder = CsvFolderColumn(name = "group", delimiter = '/', dropRoot = true),
    ),
)
