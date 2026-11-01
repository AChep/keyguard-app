package com.artemchep.keyguard.common.service.imports.impl.onepassword

import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.impl.CsvListColumn
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginColumns
import com.artemchep.keyguard.common.service.imports.impl.CsvLoginImporter

/**
 * 1Password 8: `Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,
 * Notes`, with logins and passwords only; the 1PUX export carries every item
 * type. 1Password 7 wrote a different file, with an item type column and a
 * column per field; it is refused rather than read as empty logins. Not
 * registered on its own: [OnePasswordImporter] reads through it and passes
 * in its format.
 */
internal class OnePasswordCsvImporter(
    format: ImportFormat,
) : CsvLoginImporter(
    format = format,
    requiredColumns = setOf("title", "url", "username", "password", "otpauth", "archived"),
    columns = CsvLoginColumns(
        title = "title",
        url = "url",
        username = "username",
        password = "password",
        note = "notes",
        totp = "otpauth",
        favorite = "favorite",
        tags = CsvListColumn(name = "tags", delimiter = ';'),
        archived = "archived",
    ),
)
