package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.attachmentPreviewFileExtension
import com.artemchep.keyguard.util.csv.CsvReader

@Immutable
data class AttachmentPreviewTable(
    /** The first row is the header. A row may have fewer cells than [columnCount]. */
    val rows: List<List<String>>,
    val columnCount: Int,
    /** The longest cell per column, in characters; sizes the monospace columns. */
    val columnLengths: List<Int>,
)

internal fun attachmentPreviewTableDelimiter(
    fileName: String,
    text: String,
): Char = if (fileName.attachmentPreviewFileExtension() == "tsv") {
    CsvReader.DELIMITER_TAB
} else {
    CsvReader.detectDelimiter(text)
}

/**
 * Returns `null` when the file should stay a plain text preview: it is empty,
 * has an unterminated quote, or has more than [maxCells] cells.
 */
internal fun parseAttachmentPreviewTable(
    text: String,
    delimiter: Char,
    maxCells: Int = TABLE_MAX_CELLS,
): AttachmentPreviewTable? {
    val rows = mutableListOf<List<String>>()
    val columnLengths = mutableListOf<Int>()
    var cellCount = 0
    for (record in CsvReader.read(text, delimiter)) {
        cellCount += record.fields.size
        if (record.malformed || cellCount > maxCells) {
            return null
        }

        record.fields.forEachIndexed { index, field ->
            if (index < columnLengths.size) {
                columnLengths[index] = maxOf(columnLengths[index], field.length)
            } else {
                columnLengths += field.length
            }
        }
        rows += record.fields
    }
    return if (rows.isNotEmpty()) {
        AttachmentPreviewTable(
            rows = rows,
            columnCount = columnLengths.size,
            columnLengths = columnLengths,
        )
    } else {
        null
    }
}

private const val TABLE_MAX_CELLS = 250_000
