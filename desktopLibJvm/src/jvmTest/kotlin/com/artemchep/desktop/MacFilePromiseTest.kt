package com.artemchep.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class MacFilePromiseTest {
    @Test
    fun `names follow single path component rules`() {
        listOf(
            "report.pdf",
            "a",
            ".hidden",
            "...",
            "a\\b",
            "日本.txt",
            "🔐.bin",
        ).forEach { name ->
            assertTrue(isValidMacFilePromiseName(name), name)
        }
        listOf(
            "",
            " ",
            "\t",
            ".",
            "..",
            "a/b",
            "/",
            "a\u0000b",
        ).forEach { name ->
            assertFalse(isValidMacFilePromiseName(name), name)
        }
    }

    @Test
    fun `names are bounded and unique ignoring case`() {
        assertTrue(isValidMacFilePromiseNames(listOf("a.txt")))
        assertTrue(isValidMacFilePromiseNames(numberedNames(NATIVE_DRAG_MAX_FILES)))
        assertFalse(isValidMacFilePromiseNames(emptyList()))
        assertFalse(isValidMacFilePromiseNames(numberedNames(NATIVE_DRAG_MAX_FILES + 1)))
        assertFalse(isValidMacFilePromiseNames(listOf("report.pdf", "REPORT.PDF")))
        assertFalse(isValidMacFilePromiseNames(listOf("Ä.txt", "a.txt", "ä.TXT")))
        assertFalse(isValidMacFilePromiseNames(listOf("a.txt", "a/b")))
    }

    @Test
    fun `start rejects invalid arguments before loading the native bridge`() {
        assertFailsWith<IllegalArgumentException> {
            startMacFilePromises(0L, listOf("report.pdf"), UnusedCallbacks)
        }
        listOf(
            emptyList(),
            listOf(".."),
            listOf("report.pdf", "Report.PDF"),
            numberedNames(NATIVE_DRAG_MAX_FILES + 1),
        ).forEach { names ->
            assertFailsWith<IllegalArgumentException>(names.joinToString()) {
                startMacFilePromises(1L, names, UnusedCallbacks)
            }
        }
    }

    @Test
    fun `callbacks declare every method native calls`() {
        // The names and JNI signatures that drag_jni.rs and file_promise.rs look up.
        val int = Int::class.javaPrimitiveType!!
        val callbacks = MacFilePromiseCallbacks::class.java
        assertMethod(callbacks, "ended", Void.TYPE, Boolean::class.javaPrimitiveType!!)
        assertMethod(callbacks, "retired", Void.TYPE)
        assertMethod(callbacks, "written", Void.TYPE, int)
        assertMethod(callbacks, "openReader", MacFilePromiseReader::class.java, int)
        val reader = MacFilePromiseReader::class.java
        assertMethod(reader, "getSize", Long::class.javaPrimitiveType!!)
        assertMethod(reader, "readAt", int, Long::class.javaPrimitiveType!!, ByteArray::class.java, int)
        assertMethod(reader, "close", Void.TYPE)
    }

    private fun assertMethod(type: Class<*>, name: String, returns: Class<*>, vararg parameters: Class<*>) =
        assertEquals(returns, type.getMethod(name, *parameters).returnType, name)

    private fun numberedNames(count: Int) = List(count) { "file-$it.bin" }

    private object UnusedCallbacks : MacFilePromiseCallbacks {
        override fun openReader(index: Int): MacFilePromiseReader = fail("Unexpected reader")

        override fun written(index: Int) = fail("Unexpected write")

        override fun ended(accepted: Boolean) = fail("Unexpected end")

        override fun retired() = fail("Unexpected retirement")
    }
}
