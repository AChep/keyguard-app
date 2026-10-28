package com.artemchep.keyguard.util.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsvReaderTest {
    private fun fields(
        text: String,
        delimiter: Char = CsvReader.DELIMITER_COMMA,
    ) = CsvReader.read(text, delimiter).map { it.fields }.toList()

    @Test
    fun `plain records split on commas and every line ending`() {
        assertEquals(
            listOf(
                listOf("a", "b", "c"),
                listOf("1", "2", "3"),
                listOf("x", "y", "z"),
                listOf("p", "q", "r"),
            ),
            fields("a,b,c\r\n1,2,3\nx,y,z\rp,q,r"),
        )
    }

    @Test
    fun `empty fields are kept`() {
        assertEquals(
            listOf(
                listOf("", "", ""),
                listOf("a", "", ""),
            ),
            fields(",,\na,,"),
        )
    }

    @Test
    fun `quoted fields carry delimiters, line breaks and escaped quotes`() {
        assertEquals(
            listOf(
                listOf("a,b", "line 1\nline 2", "say \"hi\"", ""),
            ),
            fields("\"a,b\",\"line 1\nline 2\",\"say \"\"hi\"\"\",\"\""),
        )
    }

    @Test
    fun `a quote inside an unquoted field is literal`() {
        assertEquals(
            listOf(listOf("5\" disk", "it's")),
            fields("5\" disk,it's"),
        )
    }

    @Test
    fun `text after a closing quote is appended`() {
        assertEquals(
            listOf(listOf("abcdef", "x")),
            fields("\"abc\"def,x"),
        )
    }

    @Test
    fun `byte order mark and trailing line break are dropped`() {
        assertEquals(
            listOf(listOf("name", "url")),
            fields("﻿name,url\r\n"),
        )
    }

    @Test
    fun `blank lines are skipped`() {
        assertEquals(
            listOf(listOf("a"), listOf("b")),
            fields("a\n\n\r\n\nb\n"),
        )
    }

    @Test
    fun `an unterminated quote swallows the rest and marks the record malformed`() {
        val records = CsvReader.read("a,b\n\"open,c\nd").toList()

        assertEquals(2, records.size)
        assertEquals(listOf("a", "b"), records[0].fields)
        assertEquals(listOf("open,c\nd"), records[1].fields)
        assertTrue(records[1].malformed)
    }

    @Test
    fun `records report the line they start on`() {
        val records = CsvReader.read("h1,h2\n\"multi\r\nline\",x\nlast,y").toList()

        assertEquals(listOf(1, 2, 4), records.map { it.line })
    }

    @Test
    fun `other delimiters are honored`() {
        assertEquals(
            listOf(listOf("a", "b,c", "d")),
            fields("a;\"b,c\";d", CsvReader.DELIMITER_SEMICOLON),
        )
        assertEquals(
            listOf(listOf("a", "b")),
            fields("a\tb", CsvReader.DELIMITER_TAB),
        )
    }

    @Test
    fun `quotes and line breaks are not delimiters`() {
        listOf('"', '\r', '\n').forEach { delimiter ->
            assertFailsWith<IllegalArgumentException> {
                CsvReader.read("a", delimiter)
            }
        }
    }

    @Test
    fun `delimiter detection picks the separator of the header`() {
        assertEquals(CsvReader.DELIMITER_COMMA, CsvReader.detectDelimiter("name,url,username\n"))
        assertEquals(CsvReader.DELIMITER_SEMICOLON, CsvReader.detectDelimiter("name;url;username\n"))
        assertEquals(CsvReader.DELIMITER_TAB, CsvReader.detectDelimiter("name\turl\tusername\n"))
        // A semicolon inside a quoted header field does not count.
        assertEquals(CsvReader.DELIMITER_COMMA, CsvReader.detectDelimiter("\"a;b\",c\n"))
        // Tie prefers a comma.
        assertEquals(CsvReader.DELIMITER_COMMA, CsvReader.detectDelimiter("single\n"))
    }

    @Test
    fun `empty input has no records`() {
        assertEquals(emptyList(), fields(""))
        assertEquals(emptyList(), fields("﻿"))
    }
}
