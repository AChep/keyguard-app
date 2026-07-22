package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.constants.HeaderFieldId
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.CipherSession
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlQualifiedName
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeePassDatabaseStreamingTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("streaming-password"))
    private val cipherProviders = BaseCiphers.entries + TwofishCipher

    @Test
    fun wrongCredentialsForV3FailAsInvalidKey() {
        val database = database(
            version = 3,
            cipher = BaseCiphers.Aes,
            compression = DatabaseHeader.Compression.GZip,
        )
        val encoded = Buffer()
        database.encodeTo(encoded, cipherProviders = cipherProviders)
        val wrongCredentials = Credentials.from(EncryptedValue.fromString("wrong-password"))

        assertFailsWith<CryptoError.InvalidKey> {
            KeePassDatabase.decode(
                source = encoded,
                credentials = wrongCredentials,
                cipherProviders = cipherProviders,
            )
        }
    }

    @Test
    fun allVersionsCiphersAndCompressionModesRoundTripFromChunkedSources() {
        val ciphers = listOf(BaseCiphers.Aes, BaseCiphers.ChaCha20, TwofishCipher)
        DatabaseHeader.Compression.entries.forEach { compression ->
            ciphers.forEach { cipher ->
                listOf(3, 4).forEach { version ->
                    val original = database(version, cipher, compression)
                    val encoded = Buffer()
                    original.encodeTo(encoded, cipherProviders = cipherProviders)
                    val source = ChunkedRawSource(encoded.readByteArray(), maximumChunk = 17)

                    val decoded =
                        KeePassDatabase.decode(
                            source = source.buffered(),
                            credentials = credentials,
                            cipherProviders = cipherProviders,
                        )

                    assertEquals("V$version-${cipher.uuid}-$compression", decoded.content.meta.name)
                    assertEquals("Root", decoded.content.group.name)
                }
            }
        }
    }

    @Test
    fun contentLargerThanOneBlockRoundTripsWithoutWholeBodyAdapters() {
        val marker = "stream-marker-"
        val description = marker + "x".repeat(ContentBlocks.BLOCK_SPLIT_RATE + 257)
        val original =
            database(
                version = 4,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.None,
                description = description,
            )
        val encoded = Buffer()
        original.encodeTo(encoded, cipherProviders = cipherProviders)

        val decoded =
            KeePassDatabase.decode(
                source = ChunkedRawSource(encoded.readByteArray(), maximumChunk = 31).buffered(),
                credentials = credentials,
                cipherProviders = cipherProviders,
            )

        assertEquals(description, decoded.content.meta.description)
    }

    @Test
    fun protectedExtensionsOutsideRootRoundTripInV3AndV4() {
        listOf(3, 4).forEach { version ->
            val base = database(
                version = version,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.None,
            )
            val content = base.content.copy(
                meta = base.content.meta.copy(
                    extensions = listOf(protectedExtension("MetaSecret", "meta-secret")),
                ),
                rootExtensions = listOf(protectedExtension("RootSecret", "root-secret")),
                documentExtensions = listOf(
                    protectedExtension("DocumentSecret", "document-secret"),
                ),
            )
            val original = when (base) {
                is KeePassDatabase.Ver3x -> base.copy(content = content)
                is KeePassDatabase.Ver4x -> base.copy(content = content)
            }
            val encoded = Buffer()

            original.encodeTo(encoded, cipherProviders = cipherProviders)
            val decoded = KeePassDatabase.decode(
                source = encoded,
                credentials = credentials,
                cipherProviders = cipherProviders,
            )

            assertEquals("meta-secret", decoded.content.meta.extensions.single().text())
            assertEquals("root-secret", decoded.content.rootExtensions.single().text())
            assertEquals("document-secret", decoded.content.documentExtensions.single().text())
        }
    }

    @Test
    fun finalDrainRejectsTamperedV3CiphertextTail() {
        val database =
            database(
                version = 3,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.GZip,
            )
        val encoded =
            Buffer()
                .also {
                    database.encodeTo(it, cipherProviders = cipherProviders)
                }.readByteArray()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()

        assertFails {
            KeePassDatabase.decode(encoded, credentials, cipherProviders = cipherProviders)
        }
    }

    @Test
    fun finalDrainRejectsTamperedV4TerminalHmac() {
        val database =
            database(
                version = 4,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.GZip,
            )
        val encoded =
            Buffer()
                .also {
                    database.encodeTo(it, cipherProviders = cipherProviders)
                }.readByteArray()
        // A V4 stream ends with [terminal HMAC:32][zero length:4].
        encoded[encoded.size - 36] =
            (encoded[encoded.size - 36].toInt() xor 0x01).toByte()

        assertFailsWith<FormatError.InvalidContent> {
            KeePassDatabase.decode(encoded, credentials, cipherProviders = cipherProviders)
        }
    }

    @Test
    fun malformedV3SetupClosesDecryptorSession() {
        val database =
            database(
                version = 3,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.None,
            )
        val encoded =
            Buffer()
                .also {
                    database.encodeTo(it, cipherProviders = cipherProviders)
                }.readByteArray()
                .withV3InnerRandomStream(CrsAlgorithm.None)
        val trackingCipher = TrackingCipherProvider(BaseCiphers.Aes)

        assertFailsWith<FormatError.InvalidHeader> {
            KeePassDatabase.decode(
                encoded,
                credentials,
                cipherProviders = listOf(trackingCipher),
            )
        }

        assertEquals(1, trackingCipher.decryptorCloseCount)
    }

    @Test
    fun sourceFailureWhileReadingHeaderIsRethrownUnchanged() {
        val expected = TestSourceException("header read failed")

        val actual = assertFailsWith<TestSourceException> {
            KeePassDatabase.decode(
                source = ThrowingRawSource(expected).buffered(),
                credentials = credentials,
                cipherProviders = cipherProviders,
            )
        }

        assertSame(expected, actual)
    }

    @Test
    fun sourceFailureReachedThroughXmlParserIsRethrownUnchanged() {
        val original =
            database(
                version = 4,
                cipher = BaseCiphers.Aes,
                compression = DatabaseHeader.Compression.None,
                description = "x".repeat(ContentBlocks.BLOCK_SPLIT_RATE + 257),
            )
        val encoded =
            Buffer()
                .also { original.encodeTo(it, cipherProviders = cipherProviders) }
                .readByteArray()
        val expected = TestSourceException("XML stream read failed")
        var parserEntered = false
        val parser = ArmingXmlContentParser {
            parserEntered = true
        }

        val actual = assertFailsWith<TestSourceException> {
            KeePassDatabase.decode(
                source = ArmedFailingRawSource(encoded, expected) { parserEntered }.buffered(),
                credentials = credentials,
                contentParser = parser,
                cipherProviders = cipherProviders,
            )
        }

        assertTrue(parserEntered)
        assertSame(expected, actual)
    }

    @Test
    fun cancellationFromContentParserIsRethrownUnchanged() {
        val original = database(
            version = 4,
            cipher = BaseCiphers.Aes,
            compression = DatabaseHeader.Compression.None,
        )
        val encoded =
            Buffer()
                .also { original.encodeTo(it, cipherProviders = cipherProviders) }
                .readByteArray()
        val expected = CancellationException("cancelled")
        val parser = ThrowingXmlContentParser(expected)

        val actual = assertFailsWith<CancellationException> {
            KeePassDatabase.decode(
                data = encoded,
                credentials = credentials,
                contentParser = parser,
                cipherProviders = cipherProviders,
            )
        }

        assertSame(expected, actual)
    }

    @Test
    fun internalContentParserFailureRemainsInvalidContent() {
        val original = database(
            version = 4,
            cipher = BaseCiphers.Aes,
            compression = DatabaseHeader.Compression.None,
        )
        val encoded =
            Buffer()
                .also { original.encodeTo(it, cipherProviders = cipherProviders) }
                .readByteArray()

        assertFailsWith<FormatError.InvalidContent> {
            KeePassDatabase.decode(
                data = encoded,
                credentials = credentials,
                contentParser = ThrowingXmlContentParser(IllegalStateException("parser failed")),
                cipherProviders = cipherProviders,
            )
        }
    }

    private fun database(
        version: Int,
        cipher: CipherProvider,
        compression: DatabaseHeader.Compression,
        description: String = "",
    ): KeePassDatabase {
        val name = "V$version-${cipher.uuid}-$compression"
        val meta = Meta(name = name, description = description)
        return when (version) {
            3 -> {
                KeePassDatabase.Ver3x.create("Root", meta, credentials).let { database ->
                    database.copy(
                        header =
                            database.header.copy(
                                cipherId = cipher.uuid,
                                compression = compression,
                                transformRounds = 1U,
                            ),
                    )
                }
            }

            4 -> {
                KeePassDatabase.Ver4x.create("Root", meta, credentials).let { database ->
                    database.copy(
                        header =
                            database.header.copy(
                                cipherId = cipher.uuid,
                                compression = compression,
                                kdfParameters =
                                    KdfParameters.Aes(
                                        rounds = 1U,
                                        seed = ByteArray(32) { it.toByte() }.toByteString(),
                                    ),
                            ),
                    )
                }
            }

            else -> {
                error("Unsupported test version")
            }
        }
    }

    private fun protectedExtension(name: String, value: String) = XmlExtension(
        name = XmlQualifiedName(name),
        content = listOf(
            XmlExtensionContent.Text(
                EntryValue.Encrypted(EncryptedValue.fromString(value)),
            ),
        ),
    )

    private fun XmlExtension.text(): String = content
        .filterIsInstance<XmlExtensionContent.Text>()
        .joinToString("") { it.value.content }
}

private class TrackingCipherProvider(
    private val delegate: CipherProvider,
) : CipherProvider by delegate {
    var decryptorCloseCount: Int = 0
        private set

    override fun createDecryptor(
        key: ByteArray,
        iv: ByteArray,
    ): CipherSession = TrackingCipherSession(
        delegate = delegate.createDecryptor(key, iv),
        onClose = { decryptorCloseCount++ },
    )
}

private class TestSourceException(
    message: String,
) : Exception(message)

private class ThrowingRawSource(
    private val failure: Throwable,
) : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = throw failure

    override fun close() = Unit
}

private class ArmedFailingRawSource(
    bytes: ByteArray,
    private val failure: Throwable,
    private val isArmed: () -> Boolean,
) : RawSource {
    private val source = Buffer().apply { write(bytes) }

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (isArmed()) throw failure
        return source.readAtMostTo(sink, byteCount)
    }

    override fun close() = Unit
}

private class ArmingXmlContentParser(
    private val onEnter: () -> Unit,
) : XmlContentParser by DefaultXmlContentParser {
    override fun unmarshalContent(
        source: BufferedSource,
        innerEncryption: EncryptionSaltGenerator,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent {
        onEnter()
        return DefaultXmlContentParser.unmarshalContent(source, innerEncryption, contextBlock)
    }
}

private class ThrowingXmlContentParser(
    private val failure: Throwable,
) : XmlContentParser by DefaultXmlContentParser {
    override fun unmarshalContent(
        source: BufferedSource,
        innerEncryption: EncryptionSaltGenerator,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent = throw failure
}

private class TrackingCipherSession(
    private val delegate: CipherSession,
    private val onClose: () -> Unit,
) : CipherSession by delegate {
    override fun close() {
        onClose()
        delegate.close()
    }
}

private fun ByteArray.withV3InnerRandomStream(algorithm: CrsAlgorithm): ByteArray {
    val output = copyOf()
    var offset = 12
    while (offset + 3 <= output.size) {
        val fieldId = output[offset].toInt() and 0xff
        val fieldSize = (output[offset + 1].toInt() and 0xff) or
                ((output[offset + 2].toInt() and 0xff) shl 8)
        val dataOffset = offset + 3
        require(dataOffset + fieldSize <= output.size) { "Invalid KDBX3 header field" }

        if (fieldId == HeaderFieldId.InnerRandomStreamId.ordinal) {
            require(fieldSize == Int.SIZE_BYTES) { "Invalid inner random stream field" }
            val value = algorithm.ordinal
            repeat(Int.SIZE_BYTES) { index ->
                output[dataOffset + index] = (value ushr (index * 8)).toByte()
            }
            return output
        }
        if (fieldId == HeaderFieldId.EndOfHeader.ordinal) break
        offset = dataOffset + fieldSize
    }
    error("KDBX3 inner random stream field was not found")
}

private class ChunkedRawSource(
    bytes: ByteArray,
    private val maximumChunk: Int,
) : RawSource {
    private val source = Buffer().apply { write(bytes) }

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = source.readAtMostTo(sink, minOf(byteCount, maximumChunk.toLong()))

    override fun close() = Unit
}
