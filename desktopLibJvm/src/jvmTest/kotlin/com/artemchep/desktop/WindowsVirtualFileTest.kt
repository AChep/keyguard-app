package com.artemchep.desktop

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WindowsVirtualFileTest {
    @Test
    fun `names follow windows component rules`() {
        listOf(
            "report.pdf",
            "a",
            ".hidden",
            "CONSOLE.txt",
            "COM10",
            "nul-file",
            "\u65e5\u672c.txt",
            "\uD83D\uDD10.bin",
            "e\u0301",
        ).forEach { name ->
            assertTrue(isValidWindowsVirtualFileName(name), name)
        }
        listOf(
            "",
            ".",
            "..",
            "name.",
            "name ",
            "a/b",
            "a\\b",
            "a:b",
            "a*b",
            "a?b",
            "a\"b",
            "a<b",
            "a>b",
            "a|b",
            "a\u0000b",
            "a\tb",
            "\u001f",
            "CON",
            "con.txt",
            "Nul.tar.gz",
            "aux .txt",
            "COM1",
            "lpt9.log",
            "COM\u00b9",
            "lpt\u00b3.txt",
            "CONIN$",
            "conout$.x",
            "a\uD83D",
            "\uDD10a",
        ).forEach { name ->
            assertFalse(isValidWindowsVirtualFileName(name), name)
        }
    }

    @Test
    fun `names fit the descriptor with a terminator`() {
        assertTrue(isValidWindowsVirtualFileName("a".repeat(259)))
        assertFalse(isValidWindowsVirtualFileName("a".repeat(260)))
        // A surrogate pair takes two of the units.
        assertTrue(isValidWindowsVirtualFileName("\uD83D\uDD10".repeat(129)))
        assertFalse(isValidWindowsVirtualFileName("\uD83D\uDD10".repeat(130)))
    }

    @Test
    fun `file sets follow count size and case rules`() {
        assertTrue(isValidWindowsVirtualFileSet(files("a.txt", "b.txt")))
        assertTrue(isValidWindowsVirtualFileSet(listOf(WindowsVirtualFileEntry("a.txt", Long.MAX_VALUE))))
        assertTrue(isValidWindowsVirtualFileSet(files("a.txt", "a.txt.txt")))
        val names = (0..NATIVE_DRAG_MAX_FILES).map { "$it.bin" }
        assertTrue(isValidWindowsVirtualFileSet(files(*names.dropLast(1).toTypedArray())))
        assertFalse(isValidWindowsVirtualFileSet(files(*names.toTypedArray())))
        assertFalse(isValidWindowsVirtualFileSet(emptyList()))
        assertFalse(isValidWindowsVirtualFileSet(listOf(WindowsVirtualFileEntry("a.txt", -1L))))
        assertFalse(isValidWindowsVirtualFileSet(files("a.txt", "CON")))
        listOf(
            "report.pdf" to "Report.PDF",
            "\u00e4.txt" to "\u00c4.txt",
            "\u03c3" to "\u03a3",
            "stra\u00dfe" to "STRASSE",
        ).forEach { (first, second) ->
            assertFalse(isValidWindowsVirtualFileSet(files(first, "x", second)), "$first $second")
        }
    }

    @Test
    fun `start rejects invalid arguments before loading the native bridge`() {
        listOf(
            emptyList(),
            listOf(WindowsVirtualFileEntry("report.pdf", -1L)),
            files("report.pdf", "REPORT.pdf"),
            files(*(0..NATIVE_DRAG_MAX_FILES).map { "$it.bin" }.toTypedArray()),
        ).forEach { files ->
            assertFailsWith<IllegalArgumentException>(files.joinToString { it.name }) {
                startWindowsVirtualFiles(files, UnusedCallbacks)
            }
        }
        listOf("", "a/b", "CON.txt", "name.").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) {
                startWindowsVirtualFiles(files("ok.txt", name), UnusedCallbacks)
            }
        }
    }

    private fun files(vararg names: String): List<WindowsVirtualFileEntry> =
        names.map { WindowsVirtualFileEntry(it, 0L) }

    private object UnusedCallbacks : WindowsVirtualFileCallbacks {
        override fun openReader(index: Int): WindowsVirtualFileReader = fail("Unexpected reader")

        override fun ended(accepted: Boolean) = fail("Unexpected end")

        override fun retired() = fail("Unexpected retirement")
    }
}
