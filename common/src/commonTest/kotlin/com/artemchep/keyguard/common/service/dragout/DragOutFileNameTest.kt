package com.artemchep.keyguard.common.service.dragout

import kotlin.test.Test
import kotlin.test.assertEquals

class DragOutFileNameTest {
    @Test
    fun `names satisfy the Windows shell rules`() {
        assertEquals("report.pdf", dragOutFileName("report.pdf"))
        assertEquals("_CON .txt", dragOutFileName("CON .txt"))
        assertEquals("_com0.log", dragOutFileName("com0.log"))
        assertEquals("_LPT¹", dragOutFileName("LPT¹"))
        assertEquals("_conin$.txt", dragOutFileName("conin$.txt"))
        assertEquals("a", dragOutFileName("a. ."))
        assertEquals("a_b_.txt", dragOutFileName("a<b?.txt"))
        assertEquals("attachment", dragOutFileName(" . "))
        assertEquals("a_b.txt", dragOutFileName("a\uD800b.txt"))
        assertEquals("\uD83D\uDE00.txt", dragOutFileName("\uD83D\uDE00.txt"))
    }

    @Test
    fun `names in one drop are unique ignoring case`() {
        assertEquals(
            listOf("a.txt", "A (2).txt", "a (3).txt", "b", "b (2)", ".env", ".env (2)"),
            distinctDragOutFileNames(listOf("a.txt", "A.txt", "a.txt", "b", "b", ".env", ".env")),
        )
    }
}
