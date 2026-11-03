package com.artemchep.keyguard.util.io

import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecodingTest {
    @Test
    fun honorsByteOrderMarks() {
        val text = "naïve €"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, decodeTextDetectingEncoding(bom + text.encodeToByteArray()))
        assertEquals(text, decodeTextDetectingEncoding(utf16(text, littleEndian = true)))
        assertEquals(text, decodeTextDetectingEncoding(utf16(text, littleEndian = false)))
        assertEquals(text, decodeTextDetectingEncoding(text.encodeToByteArray()))
    }

    @Test
    fun textThatIsNotUtf8DecodesAsWindows1252() {
        // "café €" as a spreadsheet application saves it on Windows.
        val bytes = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x80.toByte())
        assertEquals("café €", decodeTextDetectingEncoding(bytes))
    }

    private fun utf16(
        text: String,
        littleEndian: Boolean,
    ): ByteArray {
        val bom = if (littleEndian) {
            byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        } else {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        }
        val body = text.flatMap { char ->
            val hi = (char.code shr 8).toByte()
            val lo = (char.code and 0xFF).toByte()
            if (littleEndian) listOf(lo, hi) else listOf(hi, lo)
        }
        return bom + body.toByteArray()
    }
}
