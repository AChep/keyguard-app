package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedTitleFromUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.util.csv.CsvReader
import com.artemchep.keyguard.util.csv.CsvRecord
import kotlin.time.Instant

internal const val IMPORT_EXTENSION_CSV = "csv"

/**
 * The columns of a CSV header, matched by trimmed name ignoring case and
 * underscores, so `user_name`, `userName` and `username` are one column —
 * exporters spell the same header differently per platform. The first of
 * several equally named columns wins.
 */
internal class CsvHeader(
    val names: List<String>,
) {
    private val indexByName: Map<String, Int> = buildMap {
        names.forEachIndexed { index, name ->
            val key = normalize(name)
            if (key !in this) {
                put(key, index)
            }
        }
    }

    fun containsAll(columns: Collection<String>): Boolean =
        columns.all { normalize(it) in indexByName }

    fun indexOf(column: String): Int? = indexByName[normalize(column)]

    private fun normalize(name: String) = name
        .trim()
        .lowercase()
        .replace("_", "")
}

/**
 * One data record, read through its file's [header].
 */
internal class CsvRow(
    private val header: CsvHeader,
    val record: CsvRecord,
) {
    val hasExpectedWidth: Boolean get() = record.fields.size == header.names.size

    /**
     * The value of [column], or `null` when the column is absent or the value
     * is empty. Values are not trimmed: whitespace may be part of a password.
     */
    operator fun get(column: String): String? = header.indexOf(column)
        ?.let(record.fields::getOrNull)
        ?.takeIf { it.isNotEmpty() }
}

/**
 * A CSV export whose first record is its header. The rows can be read once.
 */
internal class CsvTable(
    val header: CsvHeader,
    val rows: Sequence<CsvRow>,
)

/**
 * The records of an exported CSV, in the delimiter it was saved with. A
 * spreadsheet pads a sheet with records of empty cells; they hold no item, so
 * they are left out rather than counted.
 */
internal fun readCsvRecords(
    text: String,
): Sequence<CsvRecord> = CsvReader.read(text, CsvReader.detectDelimiter(text))
    .filter { record -> record.malformed || record.fields.any { it.isNotBlank() } }

/**
 * Reads [text] as a CSV whose first record is a header, or returns `null`
 * when it has no records at all or its header is malformed.
 */
internal fun readCsvTable(
    text: String,
): CsvTable? {
    val records = readCsvRecords(text).iterator()
    val headerRecord = if (records.hasNext()) records.next() else null
    if (headerRecord == null || headerRecord.malformed) {
        return null
    }
    val header = CsvHeader(headerRecord.fields)
    return CsvTable(
        header = header,
        rows = records
            .asSequence()
            .map { CsvRow(header, it) },
    )
}

/**
 * Plans one headerless CSV record with [map], reported under [title] if mapping
 * fails. An unterminated quoted record is counted without a title: it swallowed
 * the rest of the file, so neither its fields nor its title can be trusted.
 */
internal inline fun ImportPlanBuilder.csvRecord(
    record: CsvRecord,
    title: String?,
    map: () -> Unit,
) {
    if (record.malformed) {
        // A fragment may contain a secret where a title would normally be.
        skip(ImportSkipReason.MalformedRecord)
    } else {
        this.record(title, map)
    }
}

/**
 * Validates the full header width before mapping a row. Empty fields count as
 * columns; absent fields do not. Never pad or truncate a malformed record.
 */
internal inline fun ImportPlanBuilder.csvRow(
    row: CsvRow,
    titleOf: (CsvRow) -> String?,
    map: (String?) -> Unit,
) {
    if (row.record.malformed || !row.hasExpectedWidth) {
        skip(ImportSkipReason.MalformedRecord)
    } else {
        val title = titleOf(row)
        record(title) { map(title) }
    }
}

/**
 * Plans every row on its own, as [csvRow] does. [map] gets the row with
 * the title [titleOf] reports it under.
 */
internal inline fun ImportPlanBuilder.csvRows(
    rows: Sequence<CsvRow>,
    titleOf: (CsvRow) -> String?,
    map: (CsvRow, String?) -> Unit,
) {
    rows.forEach { row ->
        csvRow(row, titleOf) { title ->
            map(row, title)
        }
    }
}

/**
 * An importer of a CSV export whose first record is a header.
 *
 * A file whose header lacks any of the [requiredColumns] is not of this
 * format and fails the import; past the header, every record maps on its own
 * and a record that cannot be mapped is a counted skip.
 */
internal abstract class CsvImporter : Importer {
    /**
     * The columns that identify the format, compared case-insensitively.
     */
    protected abstract val requiredColumns: Set<String>

    final override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult = parseText(
        text = input.readText(),
        now = now,
    )

    /**
     * Parses an export already read into [text].
     */
    fun parseText(
        text: String,
        now: Instant,
    ): ImportResult {
        val table = readCsvTable(text)
            ?.takeIf { it.header.containsAll(requiredColumns) }
            ?: return ImportResult.Failure(ImportError.Parse)
        val plan = ImportPlanBuilder(format)
        plan.csvRows(
            rows = table.rows,
            titleOf = ::titleOf,
        ) { row, _ ->
            mapRow(row, now, plan)
        }
        return ImportResult.Success(plan.build())
    }

    /**
     * The title a skipped record is reported under.
     */
    protected abstract fun titleOf(row: CsvRow): String?

    protected abstract fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    )
}

/**
 * A column holding a list, such as tags, split on [delimiter].
 */
internal data class CsvListColumn(
    val name: String,
    val delimiter: Char,
)

/**
 * A column holding a folder path, split on [delimiter]. [dropRoot] drops the
 * first segment, a root that stands for the export itself.
 */
internal data class CsvFolderColumn(
    val name: String,
    val delimiter: Char,
    val dropRoot: Boolean = false,
)

/**
 * The columns a CSV of logins keeps each login member in; `null` for a member
 * the format has no column for.
 */
internal data class CsvLoginColumns(
    val title: String,
    /** Names an untitled login after the host of its address. */
    val titleFromUrl: Boolean = true,
    val url: String,
    val username: String,
    val password: String,
    val note: String? = null,
    val totp: String? = null,
    /** A column of `true` and `false`. */
    val favorite: String? = null,
    val tags: CsvListColumn? = null,
    /** A column of `true` and `false`. */
    val archived: String? = null,
    val folder: CsvFolderColumn? = null,
)

/**
 * An importer of a CSV of logins only, whose [columns] map straight onto
 * login members.
 */
internal open class CsvLoginImporter(
    final override val format: ImportFormat,
    final override val requiredColumns: Set<String>,
    private val columns: CsvLoginColumns,
) : CsvImporter() {
    final override fun titleOf(row: CsvRow): String? = row[columns.title].orNullIfBlank()
        ?: if (columns.titleFromUrl) importedTitleFromUriOrNull(row[columns.url]) else null

    final override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val folderKey = columns.folder?.let { folder ->
            row[folder.name]
                ?.split(folder.delimiter)
                ?.drop(if (folder.dropRoot) 1 else 0)
                ?.let(plan::folder)
        }
        val meta = ImportedItemMeta(
            now = now,
            title = titleOf(row),
            note = columns.note?.let(row::get),
            favorite = columns.favorite?.let(row::get).isTrue(),
            uris = listOfNotNull(importedUriOrNull(row[columns.url])),
            tags = columns.tags
                ?.let { tags ->
                    row[tags.name]
                        ?.split(tags.delimiter)
                        ?.map { it.trim() }
                }
                .orEmpty(),
            folderKey = folderKey,
            archived = columns.archived?.let(row::get).isTrue(),
        )
        plan.login(
            meta = meta,
            username = row[columns.username],
            password = row[columns.password],
            totp = columns.totp?.let(row::get),
        )
    }

    private fun String?.isTrue() = this?.trim()?.equals("true", ignoreCase = true) == true
}
