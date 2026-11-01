package com.artemchep.keyguard.common.service.imports.impl.keeper

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.csvRecord
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.impl.readCsvRecords
import com.artemchep.keyguard.common.service.imports.impl.readText
import com.artemchep.keyguard.common.service.imports.impl.sshKeyOrNote
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedTitleFromUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.util.csv.CsvRecord
import kotlin.time.Instant

private const val COLUMN_FOLDER = 0
private const val COLUMN_TITLE = 1
private const val COLUMN_LOGIN = 2
private const val COLUMN_PASSWORD = 3
private const val COLUMN_URL = 4
private const val COLUMN_NOTES = 5
private const val COLUMN_SHARED_FOLDER = 6
private const val COLUMN_FIRST_CUSTOM = 7
private const val MIN_COLUMNS = COLUMN_NOTES + 1

private const val FOLDER_DELIMITER = '\\'

/** Keeper's own key for a one-time password, and the older one. */
private const val TOTP_FIELD = "\$oneTimeCode"
private const val TOTP_FIELD_LEGACY = "TFC:Keeper"

private const val RECORD_TYPE_FIELD = "\$type"
private const val RECORD_TYPE_SSH_KEY = "sshKeys"

/** Keeper Commander's record id and type are not user fields. */
private val pseudoFields = setOf("\$record_uid", RECORD_TYPE_FIELD)

private val sshKeyFieldTypes = setOf("keyPair", "privateKey")

/** The typed fields that hold secrets. */
private val hiddenFieldTypes = setOf("secret", "pinCode", "password", "oneTimeCode") + sshKeyFieldTypes

/** Permissions Keeper appends to a shared folder's name. */
private val sharedFolderPermissions = listOf("#edit", "#reshare")

/**
 * Keeper: a CSV without a header. The columns are folder, title, login,
 * password, website, notes and shared folder, then name and value pairs of
 * custom fields.
 *
 * A record in a shared folder sits at `<shared folder>\<folder>`. Folder
 * paths nest with `\`, and `\\` is a literal backslash. A one-time password
 * is the custom field `$oneTimeCode`, or `TFC:Keeper` in older exports; a
 * typed custom field is named `$<type>:<label>`.
 */
internal class KeeperCsvImporter(
    private val sshKeyImportService: SshKeyImportService,
) : Importer {
    override val format = ImportFormat(
        id = ImportFormatId.Keeper,
        title = "Keeper",
        extensions = listOf(IMPORT_EXTENSION_CSV),
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val records = readCsvRecords(input.readText())
            .filterNot(::isHeader)
            .toList()
        if (records.none { it.fields.size >= MIN_COLUMNS }) {
            return ImportResult.Failure(ImportError.Parse)
        }
        val plan = ImportPlanBuilder(format)
        records.forEach { record ->
            val title = record.fields.getOrNull(COLUMN_TITLE).orNullIfBlank()
            plan.csvRecord(record, title) {
                require(record.fields.size >= MIN_COLUMNS) {
                    "The record misses columns every Keeper record has"
                }
                mapRecord(record.fields, title, now, plan)
            }
        }
        return ImportResult.Success(plan.build())
    }

    private fun mapRecord(
        values: List<String>,
        title: String?,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val exportedFields = values
            .drop(COLUMN_FIRST_CUSTOM)
            .chunked(2)
            .map { pair -> pair[0].trim() to pair.getOrNull(1) }
        val recordType = exportedFields.firstOrNull { it.first == RECORD_TYPE_FIELD }?.second
        val custom = exportedFields.filter { (name, _) -> name !in pseudoFields }
        val sharedFolder = values.getOrNull(COLUMN_SHARED_FOLDER)
            ?.let(::stripPermissions)
            .orEmpty()
        val folderPath = splitFolderPath(sharedFolder) + splitFolderPath(values[COLUMN_FOLDER])
        val folderKey = plan.folder(folderPath)
        val meta = ImportedItemMeta(
            now = now,
            title = title ?: importedTitleFromUriOrNull(values[COLUMN_URL]),
            note = values[COLUMN_NOTES],
            folderKey = folderKey,
        )
        if (recordType == RECORD_TYPE_SSH_KEY) {
            mapSshKey(values, meta, custom, plan)
            return
        }
        val totp = custom.firstOrNull { (name, _) -> isTotpField(name) }
        plan.login(
            meta = meta.copy(
                uris = listOfNotNull(importedUriOrNull(values[COLUMN_URL])),
            ),
            username = values[COLUMN_LOGIN],
            password = values[COLUMN_PASSWORD],
            totp = totp?.second,
            fields = custom
                .filter { it !== totp }
                .mapNotNull { (name, value) -> customField(name, value) },
        )
    }

    private fun mapSshKey(
        values: List<String>,
        meta: ImportedItemMeta,
        custom: List<Pair<String, String?>>,
        plan: ImportPlanBuilder,
    ) {
        val keyField = custom.firstOrNull { (name, value) ->
            typedFieldType(name) in sshKeyFieldTypes && !value.isNullOrBlank()
        }
        plan.sshKeyOrNote(
            meta = meta,
            privateKeys = listOf(keyField?.second),
            sshKeyImportService = sshKeyImportService,
            // Commander exports the record's first password field, which on
            // an SSH key record is the key's passphrase.
            passphrase = values[COLUMN_PASSWORD],
        ) { sshKey ->
            // SSH items and notes have no login properties. Keep these columns as
            // user fields so saving the request cannot discard them.
            listOfNotNull(
                importedFieldOrNull("Username", values[COLUMN_LOGIN]),
                importedFieldOrNull("Passphrase", values[COLUMN_PASSWORD], hidden = true),
                importedFieldOrNull("URL", values[COLUMN_URL]),
            ) + custom
                // Consume only the converted key; other keys stay hidden and recoverable.
                .filterNot { sshKey != null && it === keyField }
                .mapNotNull { (name, value) -> customField(name, value) }
        }
    }
}

/**
 * Keeper writes no header, but a file saved back from a spreadsheet may have
 * gained one.
 */
private fun isHeader(record: CsvRecord): Boolean =
    record.fields.getOrNull(COLUMN_TITLE)?.trim()?.equals("title", ignoreCase = true) == true &&
        record.fields.getOrNull(COLUMN_LOGIN)?.trim()?.equals("login", ignoreCase = true) == true

private fun isTotpField(name: String) = name == TOTP_FIELD_LEGACY ||
    name == TOTP_FIELD ||
    name.startsWith("$TOTP_FIELD:")

private fun stripPermissions(
    name: String,
): String {
    var result = name.trim()
    while (true) {
        val suffix = sharedFolderPermissions.firstOrNull { result.endsWith(it) }
            ?: return result
        result = result.removeSuffix(suffix)
    }
}

/**
 * Splits a folder path on `\`; `\\` stands for a backslash in a name.
 */
private fun splitFolderPath(
    path: String,
): List<String> {
    val segments = mutableListOf<String>()
    val segment = StringBuilder()
    var index = 0
    while (index < path.length) {
        val c = path[index]
        if (c == FOLDER_DELIMITER && path.getOrNull(index + 1) == FOLDER_DELIMITER) {
            segment.append(FOLDER_DELIMITER)
            index += 2
        } else if (c == FOLDER_DELIMITER) {
            segments += segment.toString()
            segment.clear()
            index++
        } else {
            segment.append(c)
            index++
        }
    }
    segments += segment.toString()
    return segments.filter { it.isNotBlank() }
}

/**
 * A custom field. A typed one, `$<type>:<label>`, is shown by its label, or
 * by its type when it has none.
 */
private fun customField(
    name: String,
    value: String?,
): DSecret.Field? {
    val type = typedFieldType(name)
        ?: return importedFieldOrNull(name, value, hidden = isTotpField(name))
    val label = name.substringAfter(':', missingDelimiterValue = "")
    return importedFieldOrNull(
        name = label.orNullIfBlank() ?: type,
        value = value,
        hidden = type in hiddenFieldTypes,
    )
}

private fun typedFieldType(name: String): String? = name
    .takeIf { it.startsWith('$') }
    ?.removePrefix("$")
    ?.substringBefore(':')
