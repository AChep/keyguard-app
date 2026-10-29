package com.artemchep.keyguard.feature.attachmentpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AttachmentPreviewTableTest {
    @Test
    fun `ragged rows size columns by the widest row`() {
        val table = assertNotNull(
            parseAttachmentPreviewTable(
                text = "id,name\n1,Alice,admin\n22",
                delimiter = ',',
            ),
        )

        assertEquals(3, table.columnCount)
        assertEquals(listOf(2, 5, 5), table.columnLengths)
        assertEquals(listOf("22"), table.rows[2])
    }

    @Test
    fun `quoted multi-line cell stays one cell`() {
        val table = assertNotNull(
            parseAttachmentPreviewTable(
                text = "name,notes\nGitHub,\"line 1\nline 2\"\nGitLab,x",
                delimiter = ',',
            ),
        )

        assertEquals(3, table.rows.size)
        assertEquals(listOf("GitHub", "line 1\nline 2"), table.rows[1])
    }

    @Test
    fun `unterminated quote is not a table`() {
        assertNull(
            parseAttachmentPreviewTable(
                text = "name,notes\nGitHub,\"unterminated",
                delimiter = ',',
            ),
        )
    }

    @Test
    fun `too many cells is not a table`() {
        val text = "a,b\nc,d\ne,f"
        assertNotNull(parseAttachmentPreviewTable(text, ',', maxCells = 6))
        assertNull(parseAttachmentPreviewTable(text, ',', maxCells = 5))
    }

    @Test
    fun `empty text is not a table`() {
        assertNull(parseAttachmentPreviewTable("", ','))
        assertNull(parseAttachmentPreviewTable("\n\n", ','))
    }

    @Test
    fun `tsv files use the tab delimiter`() {
        assertEquals('\t', attachmentPreviewTableDelimiter("data.TSV", "a,b,c\td"))
        assertEquals('\t', attachmentPreviewTableDelimiter("data.tsv.gz", "a,b,c\td"))
        assertEquals(',',attachmentPreviewTableDelimiter("data.csv", "a,b,c\td"))
    }
}
