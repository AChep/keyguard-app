package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.CipherSession
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.gzip
import kotlinx.io.RawSource
import kotlinx.io.buffered
import okio.Buffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.io.Buffer as KotlinxBuffer

class Kdbx3CipherCompletionTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("cipher-completion-test"))
    private val attachment = "attachment".encodeToByteArray()

    @Test
    fun bothReadersFinishOnceAcrossAlignmentsCompressionAndChunks() {
        DatabaseHeader.Compression.entries.forEach { compression ->
            repeat(16) { alignment ->
                val data = fixture(compression = compression, alignment = alignment)
                Reader.entries.forEach { reader ->
                    listOf(1, 17, 65536).forEach { chunk ->
                        val cipher = CompletionTrackingCipher()
                        read(reader, data, cipher, chunk)
                        assertEquals(1, cipher.finishCount, "$reader/$compression/$alignment/$chunk")
                        assertEquals(1, cipher.closeCount)
                    }
                }
            }
        }
    }

    @Test
    fun bothReadersRejectInvalidPaddingEvenWhenHeaderValidationIsDisabled() {
        val data = fixture(invalidPadding = true)
        assertFailsWith<CryptoError.InvalidKey> {
            KeePassDatabase.decode(data, credentials)
        }
        Reader.entries.forEach { reader ->
            listOf(true, false).forEach { validateHashes ->
                listOf(1, 17, 65536).forEach { chunk ->
                    val cipher = CompletionTrackingCipher()
                    assertFailsWith<CryptoError.InvalidKey> {
                        read(reader, data, cipher, chunk, validateHashes = validateHashes)
                    }
                    assertEquals(1, cipher.finishCount)
                    assertEquals(1, cipher.closeCount)
                }
            }
        }
    }

    @Test
    fun ignoredPlaintextTailStillRequiresValidCipherCompletion() {
        // Cross both Okio read-ahead and native cipher chunk boundaries.
        val tailBytes = 2 * 65536
        Reader.entries.forEach { reader ->
            val cipher = CompletionTrackingCipher()
            read(reader, fixture(tailBytes = tailBytes), cipher)
            assertEquals(1, cipher.finishCount)
            assertFailsWith<CryptoError.InvalidKey> {
                read(reader, fixture(tailBytes = tailBytes, invalidPadding = true))
            }
        }
    }

    @Test
    fun bothReadersRejectTruncatedCiphertext() {
        val data = fixture()
        Reader.entries.forEach { reader ->
            listOf(1, 15, 16, 17).forEach { removed ->
                val failure = runCatching { read(reader, data.copyOf(data.size - removed)) }.exceptionOrNull()
                assertTrue(failure is CryptoError || failure is FormatError, "$reader/$removed: $failure")
            }
        }
    }

    @Test
    fun sourceFailureDuringCompletionIsPreserved() {
        val data = fixture()
        Reader.entries.forEach { reader ->
            val failure = CancellationException("source failed at EOF")
            val cipher = CompletionTrackingCipher(closeFailure = IllegalStateException("close failed"))
            val actual = assertFailsWith<CancellationException> {
                read(reader, data, cipher, eofFailure = failure)
            }
            assertSame(failure, actual)
            assertEquals(0, cipher.finishCount)
            assertEquals(1, cipher.closeCount)
            assertEquals("close failed", actual.suppressedExceptions.single().message)
        }
    }

    @Test
    fun invalidHeaderDoesNotDrainCipherDuringCleanup() {
        Reader.entries.forEach { reader ->
            val cipher = CompletionTrackingCipher(closeFailure = IllegalStateException("close failed"))
            val actual = assertFailsWith<FormatError.InvalidHeader> {
                read(reader, fixture(invalidHeaderHash = true), cipher)
            }
            assertEquals(0, cipher.finishCount)
            assertEquals(1, cipher.closeCount)
            assertEquals("close failed", actual.suppressedExceptions.single().message)
        }
    }

    @Test
    fun visitorCancellationDoesNotFinalizeDuringCleanup() {
        val data = fixture()
        val cipher = CompletionTrackingCipher()
        val failure = CancellationException("visitor cancelled")
        val actual = assertFailsWith<CancellationException> {
            read(Reader.Binaries, data, cipher, visitorFailure = failure)
        }
        assertSame(failure, actual)
        assertEquals(0, cipher.finishCount)
        assertEquals(1, cipher.closeCount)
    }

    @Test
    fun cancellationIsCheckedDuringCipherCompletion() {
        val data = fixture(tailBytes = 4 * 65536)
        val raw = CompletionTestSource(data, 65536, null)
        val cipher = CompletionTrackingCipher()
        val failure = CancellationException("cancelled while draining ciphertext tail")
        raw.buffered().use { source ->
            val actual = assertFailsWith<CancellationException> {
                KeePassDatabase.visitBinaryContents(
                    source = source,
                    credentials = credentials,
                    cipherProviders = listOf(cipher),
                    visitor = KdbxBinaryContentVisitor { _, _ -> },
                    // This point lies well beyond XML/block read-ahead and is only
                    // reached while draining the enclosing cipher after parsing.
                    checkCancellation = { if (raw.bytesRead >= data.size - 65536) throw failure },
                )
            }
            assertSame(failure, actual)
            assertFalse(raw.closed)
        }
        assertTrue(raw.bytesRead < data.size)
        assertEquals(0, cipher.finishCount)
        assertEquals(1, cipher.closeCount)
    }

    @Test
    fun invalidPaddingRemainsPrimaryWhenClosingFails() {
        Reader.entries.forEach { reader ->
            val cipher = CompletionTrackingCipher(closeFailure = IllegalStateException("close failed"))
            val actual = assertFailsWith<CryptoError.InvalidKey> {
                read(reader, fixture(invalidPadding = true), cipher)
            }
            assertEquals("close failed", actual.suppressedExceptions.single().message)
            assertEquals(1, cipher.finishCount)
            assertEquals(1, cipher.closeCount)
        }
    }

    private fun fixture(
        compression: DatabaseHeader.Compression = DatabaseHeader.Compression.None,
        alignment: Int = 0,
        tailBytes: Int = 0,
        invalidPadding: Boolean = false,
        invalidHeaderHash: Boolean = false,
    ): ByteArray {
        val header = DatabaseHeader.Ver3x.create().copy(compression = compression, transformRounds = 1U)
        val headerData = Buffer().apply { header.writeTo(this) }.readByteArray()
        val headerHash = Buffer().write(headerData).sha256().let {
            if (invalidHeaderHash) Buffer().writeUtf8("wrong header").sha256() else it
        }
        val xml = """
            <KeePassFile>
              <Meta>
                <HeaderHash>${headerHash.base64()}</HeaderHash>
                <Binaries><Binary ID="0" Compressed="False">YXR0YWNobWVudA==</Binary></Binaries>
              </Meta>
              <Root>
                <Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Root</Name></Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
        """.trimIndent()
        // With one content block, start bytes plus both block headers total 112 bytes.
        // Align the terminal so the reader can reach it without releasing the padding block.
        val xmlBytes = (xml + " ".repeat((16 - xml.length % 16) % 16 + alignment)).encodeToByteArray()
        val payload = when (compression) {
            DatabaseHeader.Compression.None -> xmlBytes
            DatabaseHeader.Compression.GZip -> xmlBytes.gzip()
        }
        val plaintext = Buffer().write(header.streamStartBytes)
        val blocks = ContentBlocks.ver3Sink(plaintext)
        blocks.write(Buffer().write(payload), payload.size.toLong())
        blocks.finish()
        plaintext.write(ByteArray(tailBytes))
        if (invalidPadding) {
            check(plaintext.size % 16 == 0L)
            // Encrypt an all-zero final block, then remove the additional valid PKCS7
            // block emitted by the encryptor. Zero is never a valid padding length.
            plaintext.write(ByteArray(16))
        }
        val transformedKey = KeyTransform.transformedKey(BaseKdfProvider, header, credentials)
        val key = KeyTransform.masterKey(header.masterSeed.toByteArray(), transformedKey)
        try {
            val encrypted = BaseCiphers.Aes.createEncryptor(key, header.encryptionIV.toByteArray()).use { session ->
                transform(session, plaintext.readByteArray())
            }
            val ciphertext = if (invalidPadding) encrypted.copyOf(encrypted.size - 16) else encrypted
            if (invalidPadding) {
                // Independent provider control: prove that the fixture's padding fails.
                assertFailsWith<CryptoError.InvalidKey> {
                    BaseCiphers.Aes.createDecryptor(key, header.encryptionIV.toByteArray()).use { session ->
                        transform(session, ciphertext)
                    }
                }
            }
            return headerData + ciphertext
        } finally {
            key.fill(0)
            transformedKey.fill(0)
        }
    }

    private fun transform(session: CipherSession, input: ByteArray): ByteArray {
        val output = Buffer()
        var offset = 0
        while (offset < input.size) {
            val length = minOf(65536, input.size - offset)
            output.write(session.update(input, offset, length))
            offset += length
        }
        output.write(session.finish())
        return output.readByteArray()
    }

    private fun read(
        reader: Reader,
        data: ByteArray,
        cipher: CompletionTrackingCipher = CompletionTrackingCipher(),
        chunk: Int = 65536,
        validateHashes: Boolean = true,
        eofFailure: Throwable? = null,
        visitorFailure: Throwable? = null,
    ) {
        val raw = CompletionTestSource(data, chunk, eofFailure)
        val source = raw.buffered()
        try {
            when (reader) {
                Reader.Database -> {
                    val database = KeePassDatabase.decode(
                        source = source,
                        credentials = credentials,
                        cipherProviders = listOf(cipher),
                        validateHashes = validateHashes,
                    )
                    assertEquals("Root", database.content.group.name)
                    assertContentEquals(attachment, database.binaries.values.single().getContent())
                }

                Reader.Binaries -> {
                    var visits = 0
                    KeePassDatabase.visitBinaryContents(
                        source = source,
                        credentials = credentials,
                        cipherProviders = listOf(cipher),
                        validateHashes = validateHashes,
                        visitor = KdbxBinaryContentVisitor { binary, _ ->
                            visitorFailure?.let { throw it }
                            val content = Buffer()
                            while (binary.read(content, 17) != -1L) {
                                // Consume the attachment before checking its bytes.
                            }
                            assertContentEquals(attachment, content.readByteArray())
                            visits++
                        },
                    )
                    assertEquals(1, visits)
                }
            }
        } finally {
            assertFalse(raw.closed, "Readers must leave the caller source open")
            assertTrue(raw.maximumRequest <= 65536)
            source.close()
        }
    }

    private enum class Reader { Database, Binaries }
}

private class CompletionTrackingCipher(
    private val closeFailure: Throwable? = null,
) : CipherProvider by BaseCiphers.Aes {
    var finishCount = 0
    var closeCount = 0

    override fun createDecryptor(key: ByteArray, iv: ByteArray): CipherSession {
        val delegate = BaseCiphers.Aes.createDecryptor(key, iv)
        return object : CipherSession by delegate {
            override fun finish(): ByteArray {
                finishCount++
                return delegate.finish()
            }

            override fun close() {
                closeCount++
                delegate.close()
                closeFailure?.let { throw it }
            }
        }
    }
}

private class CompletionTestSource(
    data: ByteArray,
    private val chunk: Int,
    private val eofFailure: Throwable?,
) : RawSource {
    private val source = KotlinxBuffer().apply { write(data) }
    var closed = false
    var maximumRequest = 0L
    var bytesRead = 0L

    override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long {
        maximumRequest = maxOf(maximumRequest, byteCount)
        if (source.size == 0L) eofFailure?.let { throw it }
        return source.readAtMostTo(sink, minOf(byteCount, chunk.toLong())).also {
            if (it > 0L) bytesRead += it
        }
    }

    override fun close() {
        closed = true
        source.close()
    }
}
