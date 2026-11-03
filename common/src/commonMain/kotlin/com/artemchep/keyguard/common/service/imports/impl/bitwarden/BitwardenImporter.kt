package com.artemchep.keyguard.common.service.imports.impl.bitwarden

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_JSON
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.readText
import kotlin.time.Instant

/**
 * Bitwarden, in either of its exports: the JSON, plain or
 * password-protected, or the CSV. A file whose text opens with a JSON
 * bracket is the JSON; neither CSV header starts with one.
 */
internal class BitwardenImporter(
    cryptoGenerator: CryptoGenerator,
    cipherEncryptor: CipherEncryptor,
) : Importer {
    override val format = ImportFormat(
        id = ImportFormatId.Bitwarden,
        title = "Bitwarden",
        extensions = listOf(IMPORT_EXTENSION_JSON, IMPORT_EXTENSION_CSV),
    )

    private val json = BitwardenJsonImporter(
        format = format,
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
    )

    private val csv = BitwardenCsvImporter(
        format = format,
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val text = input.readText()
        return when (text.firstOrNull { !it.isWhitespace() }) {
            '{', '[' -> json.parseText(
                text = text,
                password = password,
                now = now,
                attachments = null,
            )

            else -> csv.parseText(
                text = text,
                now = now,
            )
        }
    }
}
