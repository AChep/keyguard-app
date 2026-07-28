package com.artemchep.jna.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacClipboardWriterTest {
    @Test
    fun `writer forwards concealed clipboard contents on macos`() {
        val operations = FakeMacClipboardOperations()
        val writer = MacClipboardWriter(operations)

        assertTrue(writer.setText("password", concealed = true))
        assertEquals(
            listOf("password" to true),
            operations.writes,
        )
    }

    @Test
    fun `writer does not call native operations on another platform`() {
        val operations = FakeMacClipboardOperations(isMac = false)
        val writer = MacClipboardWriter(operations)

        assertFalse(writer.setText("password", concealed = true))
        assertEquals(emptyList(), operations.writes)
    }

    @Test
    fun `writer reports native failure`() {
        val operations = FakeMacClipboardOperations().apply {
            failure = IllegalStateException("Pasteboard unavailable")
        }
        val writer = MacClipboardWriter(operations)

        assertFalse(writer.setText("password", concealed = true))
    }

    private class FakeMacClipboardOperations(
        override val isMac: Boolean = true,
    ) : MacClipboardOperations {
        val writes = mutableListOf<Pair<String, Boolean>>()
        var failure: Throwable? = null

        override fun setText(
            value: String,
            concealed: Boolean,
        ): Boolean {
            failure?.let { throwable -> throw throwable }
            writes += value to concealed
            return true
        }
    }
}
