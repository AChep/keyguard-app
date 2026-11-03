package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParserUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgUserIdInfo
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_ids_primary
import com.artemchep.keyguard.test.TestTranslator
import com.artemchep.keyguard.test.testCopyText
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class AttachmentPreviewGpgKeyDecoderTest {
    @Test
    fun `armored public key shows details and source`() = runTest {
        val armored = "$PUBLIC_ARMOR_HEADER\n\nabc\n-----END PGP PUBLIC KEY BLOCK-----\n"
        val decoder = testGpgKeyDecoder(parser = successParser(key()))

        val content = decoder.decode("key.asc", armored.encodeToByteArray(), testCopyText())

        val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
        assertEquals(armored, gpgKey.source?.text)
        assertNotNull(gpgKey.onCopy)
        assertEquals("pub", gpgKey.secPub())
        val details = gpgKey.items.filterIsInstance<VaultViewItem.Table>().first()
        assertEquals("gpg.0.details", details.id)
        assertTrue(details.rows.any { it.value == "AAAA BBBB CCCC DDDD EEEE FFFF 0000 1111 2222 3333" })
    }

    @Test
    fun `armored private key is reported as secret`() = runTest {
        val armored = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nabc\n"
        val decoder = testGpgKeyDecoder(parser = successParser(key()))

        val content = decoder.decode("key.asc", armored.encodeToByteArray(), testCopyText())

        assertEquals("sec", assertIs<AttachmentPreviewContent.GpgKey>(content).secPub())
    }

    @Test
    fun `binary key has no source and its packet tag tells sec from pub`() = runTest {
        val decoder = testGpgKeyDecoder(parser = successParser(key()))
        mapOf(
            // New packet format: Public-Key and Secret-Key packets.
            0xC6 to "pub",
            0xC5 to "sec",
            // Legacy packet format, as GnuPG writes them.
            0x99 to "pub",
            0x95 to "sec",
        ).forEach { (header, expected) ->
            val bytes = byteArrayOf(header.toByte(), 0x01, 0x0D)

            val content = decoder.decode("key.gpg", bytes, testCopyText())

            val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
            assertNull(gpgKey.source)
            assertNull(gpgKey.onCopy)
            assertEquals(expected, gpgKey.secPub(), header.toString(16))
        }
    }

    @Test
    fun `user ids mark the primary one`() = runTest {
        val key = key(
            userIds = listOf("Alice <alice@example.com>", "Alice <alice@work.example>"),
            primaryUserId = "Alice <alice@work.example>",
        )
        val decoder = testGpgKeyDecoder(parser = successParser(key))

        val content = decoder.decode("key.asc", PUBLIC_ARMOR_HEADER.encodeToByteArray(), testCopyText())

        val userIds = assertIs<AttachmentPreviewContent.GpgKey>(content).items
            .filterIsInstance<VaultViewItem.Value>()
        assertEquals(key.userIds, userIds.map { it.value })
        assertEquals(listOf(null, TestTranslator.primaryLabel()), userIds.map { it.label })
    }

    @Test
    fun `several certificates get a header each`() = runTest {
        val decoder = testGpgKeyDecoder(
            parser = successParser(
                key(userIds = listOf("Alice")),
                key(userIds = listOf("Bob")),
            ),
        )

        val content = decoder.decode("keyring.gpg", byteArrayOf(0x99.toByte()), testCopyText())

        val sections = assertIs<AttachmentPreviewContent.GpgKey>(content).items
            .filterIsInstance<VaultViewItem.Section>()
            .filter { it.id.matches(Regex("gpg\\.\\d+\\.section")) }
        assertEquals(listOf("gpg.0.section", "gpg.1.section"), sections.map { it.id })
        assertEquals(listOf("Alice", "Bob"), sections.map { it.text })
    }

    @Test
    fun `a single certificate has no header`() = runTest {
        val decoder = testGpgKeyDecoder(parser = successParser(key()))

        val content = decoder.decode("key.gpg", byteArrayOf(0x99.toByte()), testCopyText())

        val ids = assertIs<AttachmentPreviewContent.GpgKey>(content).items.map { it.id }
        assertTrue("gpg.0.section" !in ids)
    }

    @Test
    fun `skipped certificates are reported`() = runTest {
        val decoder = testGpgKeyDecoder(
            parser = parser(GpgPublicKeyParseResult.Success(listOf(key()), skippedCertificates = 2)),
        )

        val content = decoder.decode("keyring.gpg", byteArrayOf(0x99.toByte()), testCopyText())

        val items = assertIs<AttachmentPreviewContent.GpgKey>(content).items
        assertEquals("gpg.skipped", items.first().id)
        assertIs<VaultViewItem.Info>(items.first())
    }

    @Test
    fun `expired key shows a warning`() = runTest {
        val decoder = testGpgKeyDecoder(
            parser = successParser(key(expiresAt = NOW - 1.days)),
        )

        val content = decoder.decode("key.gpg", byteArrayOf(0x99.toByte()), testCopyText())

        val ids = assertIs<AttachmentPreviewContent.GpgKey>(content).items.map { it.id }
        assertTrue("gpg.0.info.expired" in ids, ids.toString())
    }

    @Test
    fun `subkeys get their own tables`() = runTest {
        val subKey = GpgPublicSubKeyInfo(
            fingerprint = "1111222233334444555566667777888899990000",
            keyId = "8899990000",
            algorithm = "X25519",
            canSign = false,
            canEncrypt = true,
            revoked = false,
            expiresAt = null,
        )
        val decoder = testGpgKeyDecoder(parser = successParser(key(subKeys = listOf(subKey))))

        val content = decoder.decode("key.gpg", byteArrayOf(0x99.toByte()), testCopyText())

        val ids = assertIs<AttachmentPreviewContent.GpgKey>(content).items.map { it.id }
        assertTrue("gpg.0.subKeys.section" in ids, ids.toString())
        assertTrue("gpg.0.subKey.0" in ids, ids.toString())
    }

    @Test
    fun `armored file that is not a key opens as text`() = runTest {
        val signature = "-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n"
        val decoder = testGpgKeyDecoder(
            parser = parser(GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)),
        )

        val content = decoder.decode("file.asc", signature.encodeToByteArray(), testCopyText())

        assertEquals(signature, assertIs<AttachmentPreviewContent.Text>(content).text)
    }

    @Test
    fun `armored file with a byte order mark is not treated as binary`() = runTest {
        val decoder = testGpgKeyDecoder()
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "text".encodeToByteArray()

        val content = decoder.decode("file.asc", bytes, testCopyText())

        assertEquals("text", assertIs<AttachmentPreviewContent.Text>(content).text)
    }

    @Test
    fun `binary file that is not a key is an error`() = runTest {
        val decoder = testGpgKeyDecoder(
            parser = parser(GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)),
        )
        // A Public-Key Encrypted Session Key packet starts an encrypted message.
        val bytes = byteArrayOf(0x85.toByte(), 0x01, 0x0C, 0x03)

        val content = decoder.decode("message.gpg", bytes, testCopyText())

        assertEquals(AttachmentPreviewError.GpgKey, assertIs<AttachmentPreviewContent.Error>(content).type)
    }

    @Test
    fun `parser without byte support falls back to text`() = runTest {
        val decoder = testGpgKeyDecoder(parser = GpgPublicKeyParserUnsupported)

        val content = decoder.decode("key.asc", PUBLIC_ARMOR_HEADER.encodeToByteArray(), testCopyText())

        assertIs<AttachmentPreviewContent.Text>(content)
    }
}

internal fun testGpgKeyDecoder(
    parser: GpgPublicKeyParser = GpgPublicKeyParserUnsupported,
) = AttachmentPreviewGpgKeyDecoder(
    parser = parser,
    dateFormatter = InstantDateFormatter,
    translator = TestTranslator,
    clock = object : Clock {
        override fun now(): Instant = NOW
    },
)

/** A decoder whose parser reads any input as one key. */
internal fun testGpgKeyDecoderWithKey() = testGpgKeyDecoder(parser = successParser(key()))

private const val PUBLIC_ARMOR_HEADER = "-----BEGIN PGP PUBLIC KEY BLOCK-----"

private val NOW = Instant.parse("2026-01-01T00:00:00Z")

/** The value of the sec/pub row, the last row of the first key's details. */
internal fun AttachmentPreviewContent.GpgKey.secPub(): String = items
    .filterIsInstance<VaultViewItem.Table>()
    .first { it.id == "gpg.0.details" }
    .rows
    .last()
    .value

private suspend fun TestTranslator.primaryLabel(): String =
    translate(Res.string.gpg_user_ids_primary)

private fun key(
    userIds: List<String> = listOf("Alice <alice@example.com>"),
    primaryUserId: String? = null,
    expiresAt: Instant? = null,
    subKeys: List<GpgPublicSubKeyInfo> = emptyList(),
) = GpgPublicKeyInfo(
    fingerprint = "AAAABBBBCCCCDDDDEEEEFFFF0000111122223333",
    keyId = "0000111122223333",
    algorithm = "ED25519",
    bitStrength = 255,
    userIds = userIds,
    emails = emptyList(),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    expiresAt = expiresAt,
    revoked = false,
    canSign = true,
    canEncrypt = false,
    publicKeyArmored = "",
    subKeys = subKeys,
    userIdDetails = userIds.mapIndexed { index, userId ->
        GpgUserIdInfo(
            identityId = index.toString(),
            userId = userId,
            primary = userId == primaryUserId,
        )
    },
)

private fun successParser(
    vararg keys: GpgPublicKeyInfo,
) = parser(GpgPublicKeyParseResult.Success(keys.toList()))

private fun parser(
    result: GpgPublicKeyParseResult,
) = object : GpgPublicKeyParser {
    override fun parse(armored: String): GpgPublicKeyParseResult = error("Unexpected text parse")

    override fun parse(keyData: ByteArray): GpgPublicKeyParseResult = result
}

private object InstantDateFormatter : DateFormatter {
    override fun formatDate(instant: Instant): String = instant.toString()

    override fun formatDateTime(instant: Instant): String = instant.toString()

    override suspend fun formatDateShort(date: LocalDate): String = date.toString()

    override fun formatDateMedium(date: LocalDate): String = date.toString()

    override fun formatTimeShort(time: LocalTime): String = time.toString()
}
