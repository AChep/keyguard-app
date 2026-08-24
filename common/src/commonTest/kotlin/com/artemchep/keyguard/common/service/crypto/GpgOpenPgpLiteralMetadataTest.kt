package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpgOpenPgpLiteralMetadataTest {
    @Test
    fun `normalizes every recognized literal format`() {
        val formats = mapOf(
            'b' to GpgOpenPgpLiteralFormat.BINARY,
            't' to GpgOpenPgpLiteralFormat.TEXT,
            'u' to GpgOpenPgpLiteralFormat.UTF8,
            'm' to GpgOpenPgpLiteralFormat.MIME,
            'x' to GpgOpenPgpLiteralFormat.UNKNOWN,
        )

        formats.forEach { (value, expected) ->
            assertEquals(
                expected = expected,
                actual = metadata(format = value.code).normalized().format,
            )
        }
    }

    @Test
    fun `decodes a safe UTF-8 filename`() {
        assertEquals(
            expected = "ключ.txt",
            actual = metadata(fileName = "ключ.txt".encodeToByteArray())
                .normalized()
                .fileName,
        )
    }

    @Test
    fun `rejects invalid or unsafe filenames`() {
        val unsafeNames = listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(),
            " ".encodeToByteArray(),
            ".".encodeToByteArray(),
            "..".encodeToByteArray(),
            "../secret".encodeToByteArray(),
            "folder/file".encodeToByteArray(),
            "folder\\file".encodeToByteArray(),
            "file\u0000name".encodeToByteArray(),
            // Control characters: status-line/log injection (SigSpoof).
            "file\nname.txt".encodeToByteArray(),
            "file\rname.txt".encodeToByteArray(),
            "file\u001Bname.txt".encodeToByteArray(),
            // Directional overrides: extension spoofing ("gpj.exe").
        ) + UNSAFE_BIDI_CONTROL_CHARACTERS.map { character ->
            "file${character}txt.exe".encodeToByteArray()
        }

        unsafeNames.forEach { fileName ->
            assertEquals(
                expected = "",
                actual = metadata(fileName = fileName).normalized().fileName,
            )
        }
    }

    @Test
    fun `bounds packet filename by its encoded byte size`() {
        assertEquals(
            expected = "a".repeat(255),
            actual = metadata(fileName = "a".repeat(255).encodeToByteArray())
                .normalized()
                .fileName,
        )
        assertEquals(
            expected = "",
            actual = metadata(fileName = "a".repeat(256).encodeToByteArray())
                .normalized()
                .fileName,
        )
    }

    @Test
    fun `normalizes negative size and unsafe timestamps`() {
        assertEquals(
            expected = GpgOpenPgpNormalizedLiteralMetadata(
                fileName = "message.txt",
                format = GpgOpenPgpLiteralFormat.UTF8,
                modificationTimeEpochSeconds = 0L,
                originalSize = 0L,
            ),
            actual = metadata(
                fileName = "message.txt".encodeToByteArray(),
                format = 'u'.code,
                modificationTimeEpochSeconds = -1L,
                originalSize = -1L,
            ).normalized(),
        )
        assertEquals(
            expected = 0L,
            actual = metadata(
                modificationTimeEpochSeconds = Long.MAX_VALUE,
            ).normalized().modificationTimeEpochSeconds,
        )
    }

    @Test
    fun `creates a privacy preserving outbound literal filename`() {
        assertEquals("message.txt", outboundFileName(" message.txt "))
        val unsafeNames = listOf<String?>(
            null,
            "",
            " \t",
            "../message.txt",
            "folder/message.txt",
            "folder\\message.txt",
            ".",
            "\u0000message.txt",
            "message\n.txt",
        ) + UNSAFE_BIDI_CONTROL_CHARACTERS.map { character ->
            "message${character}.txt"
        }
        unsafeNames.forEach { value ->
            assertEquals("", outboundFileName(value))
        }

        val unicode = outboundFileName("🔐".repeat(100))
        assertTrue(unicode.encodeToByteArray().size <= 255)
        assertTrue(unicode.isNotEmpty())
    }

    @Test
    fun `bounds outbound literal filename by UTF-8 bytes`() {
        assertEquals("a".repeat(255), outboundFileName("a".repeat(255)))
        assertEquals("a".repeat(255), outboundFileName("a".repeat(256)))
        assertEquals(
            expected = "🔐".repeat(63),
            actual = outboundFileName("🔐".repeat(64)),
        )
    }

    @Test
    fun `preserves safe timestamp and size`() {
        assertEquals(
            expected = GpgOpenPgpNormalizedLiteralMetadata(
                fileName = "",
                format = GpgOpenPgpLiteralFormat.BINARY,
                modificationTimeEpochSeconds = 1_700_000_000L,
                originalSize = 42L,
            ),
            actual = metadata(
                modificationTimeEpochSeconds = 1_700_000_000L,
                originalSize = 42L,
            ).normalized(),
        )
    }

    private fun metadata(
        fileName: ByteArray = byteArrayOf(),
        format: Int = 'b'.code,
        modificationTimeEpochSeconds: Long = 0L,
        originalSize: Long = 0L,
    ) = GpgOpenPgpLiteralMetadata(
        fileName = fileName,
        format = format,
        modificationTimeEpochSeconds = modificationTimeEpochSeconds,
        originalSize = originalSize,
    )

    private fun outboundFileName(value: String?): String =
        GpgOpenPgpLiteralFileName.fromUntrusted(value).value

    private companion object {
        val UNSAFE_BIDI_CONTROL_CHARACTERS = listOf(
            '\u061C',
            '\u200E',
            '\u200F',
            '\u202A',
            '\u202B',
            '\u202C',
            '\u202D',
            '\u202E',
            '\u2066',
            '\u2067',
            '\u2068',
            '\u2069',
        )
    }
}
