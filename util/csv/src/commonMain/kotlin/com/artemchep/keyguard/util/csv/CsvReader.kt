package com.artemchep.keyguard.util.csv

/**
 * One record of a CSV document.
 *
 * [line] is the one-based line the record starts on, which differs from the
 * record's ordinal as soon as a quoted field spans several lines.
 */
data class CsvRecord(
    val line: Int,
    val fields: List<String>,
    /**
     * The record ends inside an unterminated quoted field, so its last field
     * swallowed the rest of the document.
     */
    val malformed: Boolean = false,
)

/**
 * Reads RFC 4180 CSV leniently, the way spreadsheet applications do:
 * - a leading byte-order mark is dropped;
 * - records end at CRLF, LF or a lone CR;
 * - a quoted field may contain delimiters, line breaks and `""` escapes;
 * - a quote inside an unquoted field is a literal character, and text after a
 *   closing quote is appended to the field instead of failing the document;
 * - blank lines are skipped rather than read as one-field records.
 */
object CsvReader {
    const val DELIMITER_COMMA = ','
    const val DELIMITER_SEMICOLON = ';'
    const val DELIMITER_TAB = '\t'

    private const val BOM = '﻿'
    private const val QUOTE = '"'

    private val delimiterCandidates = charArrayOf(
        DELIMITER_COMMA,
        DELIMITER_SEMICOLON,
        DELIMITER_TAB,
    )

    fun read(
        text: String,
        delimiter: Char = DELIMITER_COMMA,
    ): Sequence<CsvRecord> {
        require(delimiter != QUOTE && delimiter != '\r' && delimiter != '\n') {
            "Delimiter must not be a quote or a line break"
        }
        return sequence {
            val reader = Reader(text, delimiter)
            while (true) {
                val record = reader.next()
                    ?: break
                yield(record)
            }
        }
    }

    /**
     * Picks the delimiter that splits the first record into the most fields,
     * preferring a comma on a tie. Spreadsheet applications in locales with a
     * decimal comma save CSV with semicolons.
     */
    fun detectDelimiter(
        text: String,
    ): Char = delimiterCandidates.maxBy { delimiter ->
        read(text, delimiter)
            .firstOrNull()
            ?.fields
            ?.size
            ?: 0
    }

    private class Reader(
        private val text: String,
        private val delimiter: Char,
    ) {
        private var index = if (text.startsWith(BOM)) 1 else 0
        private var line = 1

        fun next(): CsvRecord? {
            while (index < text.length) {
                val record = readRecord()
                val blank = record.fields.size == 1 &&
                    record.fields[0].isEmpty() &&
                    !record.malformed
                if (!blank) {
                    return record
                }
            }
            return null
        }

        private fun readRecord(): CsvRecord {
            val startLine = line
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var malformed = false
            var end = false
            while (!end) {
                when (val c = text.getOrNull(index)) {
                    null -> end = true

                    delimiter -> {
                        fields += field.toString()
                        field.clear()
                        index++
                    }

                    '\r', '\n' -> {
                        skipLineBreak(c)
                        end = true
                    }

                    QUOTE -> {
                        index++
                        // Only a quote that opens the field starts a quoted
                        // section; elsewhere it is literal text.
                        if (field.isNotEmpty()) {
                            field.append(c)
                        } else if (!readQuoted(field)) {
                            malformed = true
                        }
                    }

                    else -> {
                        field.append(c)
                        index++
                    }
                }
            }
            fields += field.toString()
            return CsvRecord(
                line = startLine,
                fields = fields,
                malformed = malformed,
            )
        }

        /**
         * Reads a quoted section, its opening quote already consumed, into
         * [field]. Returns `false` if the text ends inside the section.
         */
        private fun readQuoted(
            field: StringBuilder,
        ): Boolean {
            while (index < text.length) {
                val c = text[index++]
                if (c != QUOTE) {
                    if (c == '\n' || (c == '\r' && text.getOrNull(index) != '\n')) {
                        line++
                    }
                    field.append(c)
                } else if (text.getOrNull(index) == QUOTE) {
                    field.append(QUOTE)
                    index++
                } else {
                    return true
                }
            }
            return false
        }

        private fun skipLineBreak(
            c: Char,
        ) {
            index++
            if (c == '\r' && text.getOrNull(index) == '\n') {
                index++
            }
            line++
        }
    }
}
