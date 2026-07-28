package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GpgClearSignedTextTest {
    @Test
    fun `split preserves raw bytes and canonical length across LF lines`() {
        val lines = splitClearTextLines("a\nb".encodeToByteArray())

        assertEquals(2, lines.size)
        assertEquals("a\n", lines[0].raw.decodeToString())
        assertEquals(1, lines[0].canonicalLength)
        assertEquals("b", lines[1].raw.decodeToString())
        assertEquals(1, lines[1].canonicalLength)
    }

    @Test
    fun `split keeps the CRLF terminator on the raw line`() {
        val lines = splitClearTextLines("a\r\nb".encodeToByteArray())

        assertEquals(2, lines.size)
        assertEquals("a\r\n", lines[0].raw.decodeToString())
        assertEquals(1, lines[0].canonicalLength)
        assertEquals("b", lines[1].raw.decodeToString())
        assertEquals(1, lines[1].canonicalLength)
    }

    @Test
    fun `split does not emit a trailing empty line for a final newline`() {
        val lines = splitClearTextLines("a\n".encodeToByteArray())

        assertEquals(1, lines.size)
        assertEquals("a\n", lines[0].raw.decodeToString())
        assertEquals(1, lines[0].canonicalLength)
    }

    @Test
    fun `split of empty input still yields one empty line`() {
        val lines = splitClearTextLines(ByteArray(0))

        assertEquals(1, lines.size)
        assertEquals("", lines[0].raw.decodeToString())
        assertEquals(0, lines[0].canonicalLength)
    }

    @Test
    fun `split canonical length ignores trailing whitespace`() {
        val lines = splitClearTextLines("a  \t\nb".encodeToByteArray())

        assertEquals(2, lines.size)
        assertEquals("a  \t\n", lines[0].raw.decodeToString())
        assertEquals(1, lines[0].canonicalLength)
    }

    @Test
    fun `parse extracts body lines after the header for LF input`() {
        val message = clearSigned(
            body = "hello world",
        )

        val parsed = parseClearSignedMessage(message)

        assertEquals(listOf("hello world"), parsed.lines.map { it.decodeToString() })
        assertEquals(true, parsed.signatureArmored.startsWith("-----BEGIN PGP SIGNATURE-----"))
    }

    @Test
    fun `parse of CRLF input matches parse of LF input`() {
        val lf = clearSigned(body = "line one\nline two")
        val crlf = lf.replace("\n", "\r\n")

        val parsedLf = parseClearSignedMessage(lf)
        val parsedCrlf = parseClearSignedMessage(crlf)

        assertEquals(
            listOf("line one", "line two"),
            parsedLf.lines.map { it.decodeToString() },
        )
        assertEquals(
            parsedLf.lines.map { it.decodeToString() },
            parsedCrlf.lines.map { it.decodeToString() },
        )
    }

    @Test
    fun `parse unescapes dash-escaped lines`() {
        val message = clearSigned(
            body = "- -----hidden\n- normal",
        )

        val parsed = parseClearSignedMessage(message)

        assertEquals(
            listOf("-----hidden", "normal"),
            parsed.lines.map { it.decodeToString() },
        )
    }

    @Test
    fun `parse strips trailing whitespace from body lines`() {
        val message = clearSigned(
            body = "trailing spaces   \tand tabs",
        )

        val parsed = parseClearSignedMessage(message)

        assertEquals(
            listOf("trailing spaces   \tand tabs"),
            parsed.lines.map { it.decodeToString() },
        )
    }

    @Test
    fun `parse strips trailing whitespace at end of a line`() {
        // The single body line has trailing whitespace that is not part of the
        // canonical content the signature covers.
        val message = clearSigned(
            body = "value   ",
        )

        val parsed = parseClearSignedMessage(message)

        assertEquals(listOf("value"), parsed.lines.map { it.decodeToString() })
    }

    @Test
    fun `parse of empty body yields a single empty line`() {
        val message = buildString {
            append("-----BEGIN PGP SIGNED MESSAGE-----\n")
            append("Hash: SHA256\n")
            append("\n")
            append("\n")
            append("-----BEGIN PGP SIGNATURE-----\n")
            append("dummy\n")
            append("-----END PGP SIGNATURE-----\n")
        }

        val parsed = parseClearSignedMessage(message)

        assertEquals(listOf(""), parsed.lines.map { it.decodeToString() })
    }

    @Test
    fun `parse keeps dash-escaped signature marker line in the body`() {
        // A body line that is itself the signature BEGIN marker is dash-escaped to
        // "- -----BEGIN PGP SIGNATURE-----" inside the signed body. The parser must not
        // mistake that escaped body line for the real trailing signature block: it has
        // to un-escape it back into the body and locate the signature at the genuine
        // block that follows.
        val message = clearSigned(
            body = "- -----BEGIN PGP SIGNATURE-----\nnormal",
        )

        val parsed = parseClearSignedMessage(message)

        assertEquals(
            listOf("-----BEGIN PGP SIGNATURE-----", "normal"),
            parsed.lines.map { it.decodeToString() },
        )
        assertTrue(
            parsed.signatureArmored.startsWith("-----BEGIN PGP SIGNATURE-----\ndummy"),
            "signatureArmored must be the real trailing block, not the dash-escaped body line",
        )
    }

    @Test
    fun `parse fails when there is no signature block`() {
        assertFailsWith<IllegalStateException> {
            parseClearSignedMessage("just some text without a signature")
        }
    }

    private fun clearSigned(
        body: String,
    ): String = buildString {
        append("-----BEGIN PGP SIGNED MESSAGE-----\n")
        append("Hash: SHA256\n")
        append("\n")
        append(body)
        append("\n")
        append("-----BEGIN PGP SIGNATURE-----\n")
        append("dummy\n")
        append("-----END PGP SIGNATURE-----\n")
    }
}
