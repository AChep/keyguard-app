package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.HeaderFieldId
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.Meta
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DecoderHeaderAuthenticationTest {
    private val credentials = Credentials.from(
        EncryptedValue.fromString("header-authentication-password"),
    )

    @Test
    fun bothDecodersRejectChangedV3HeaderHash() {
        val encoded = database(3).encode()
        // A comment changes the authenticated header bytes without changing
        // any parameters needed to decrypt and parse the original body.
        val comment = byteArrayOf(HeaderFieldId.Comment.ordinal.toByte(), 1, 0, 42)
        val changed = encoded.copyOfRange(0, 12) + comment +
            encoded.copyOfRange(12, encoded.size)

        DecoderApi.entries.forEach { api ->
            assertFailsWith<FormatError.InvalidHeader>(api.name) {
                readAndCheckCallerOwnership(api, changed, validateHashes = true)
            }
            readAndCheckCallerOwnership(api, changed, validateHashes = false)
        }
    }

    @Test
    fun bothDecodersRejectChangedV4HeaderSha256() {
        val encoded = database(4).encode()
        val headerEnd = v4HeaderEnd(encoded)
        encoded[headerEnd] = (encoded[headerEnd].toInt() xor 1).toByte()

        DecoderApi.entries.forEach { api ->
            assertFailsWith<FormatError.InvalidHeader>(api.name) {
                readAndCheckCallerOwnership(api, encoded, validateHashes = true)
            }
            readAndCheckCallerOwnership(api, encoded, validateHashes = false)
        }
    }

    @Test
    fun bothDecodersRejectChangedV4HeaderHmac() {
        val encoded = database(4).encode()
        val hmacOffset = v4HeaderEnd(encoded) + 32
        encoded[hmacOffset] = (encoded[hmacOffset].toInt() xor 1).toByte()

        DecoderApi.entries.forEach { api ->
            assertFailsWith<CryptoError.InvalidKey>(api.name) {
                readAndCheckCallerOwnership(api, encoded, validateHashes = true)
            }
            readAndCheckCallerOwnership(api, encoded, validateHashes = false)
        }
    }

    @Test
    fun cancellationFromBinaryVisitorPreservesCallerOwnershipAfterHeaderHandoff() {
        listOf(3, 4).forEach { version ->
            val binary = BinaryData.Uncompressed(false, byteArrayOf(1, 2, 3))
            val encoded = database(version).modifyBinaries {
                linkedMapOf(binary.hash to binary)
            }.encode()
            val rawSource = HeaderAuthenticationSource(encoded)
            val expected = CancellationException("Visitor cancelled")

            val source = rawSource.buffered()
            try {
                val actual = assertFailsWith<CancellationException>("KDBX $version") {
                    KeePassDatabase.visitBinaryContents(
                        source = source,
                        credentials = credentials,
                        visitor = KdbxBinaryContentVisitor { _, _ -> throw expected },
                    )
                }

                assertSame(expected, actual)
                assertEquals(0, rawSource.closeCount, "KDBX $version must leave caller input open")
            } finally {
                source.close()
                assertEquals(1, rawSource.closeCount, "Caller must close the input")
            }
        }
    }

    private fun readAndCheckCallerOwnership(
        api: DecoderApi,
        encoded: ByteArray,
        validateHashes: Boolean,
    ) {
        val rawSource = HeaderAuthenticationSource(encoded)
        val source = rawSource.buffered()
        try {
            when (api) {
                DecoderApi.Database -> {
                    val decoded = KeePassDatabase.decode(
                        source = source,
                        credentials = credentials,
                        validateHashes = validateHashes,
                    )
                    assertEquals("Header authentication", decoded.content.meta.name)
                }

                DecoderApi.BinaryVisitor -> KeePassDatabase.visitBinaryContents(
                    source = source,
                    credentials = credentials,
                    validateHashes = validateHashes,
                    visitor = KdbxBinaryContentVisitor { _, _ ->
                        error("The test database has no binaries")
                    },
                )
            }
        } finally {
            try {
                assertEquals(0, rawSource.closeCount, "${api.name} must leave caller input open")
            } finally {
                source.close()
                assertEquals(1, rawSource.closeCount, "Caller must close the input")
            }
        }
    }

    private fun database(version: Int): KeePassDatabase = when (version) {
        3 -> KeePassDatabase.Ver3x.create(
            rootName = "Root",
            meta = Meta(name = "Header authentication"),
            credentials = credentials,
        ).let { database ->
            database.copy(
                header = database.header.copy(
                    compression = DatabaseHeader.Compression.None,
                    transformRounds = 1U,
                ),
            )
        }

        4 -> KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Header authentication"),
            credentials = credentials,
        ).let { database ->
            database.copy(
                header = database.header.copy(
                    compression = DatabaseHeader.Compression.None,
                    kdfParameters = KdfParameters.Aes(
                        rounds = 1U,
                        seed = ByteArray(32) { it.toByte() }.toByteString(),
                    ),
                ),
            )
        }

        else -> error("Unsupported test version")
    }

    private fun v4HeaderEnd(encoded: ByteArray): Int {
        val source = okio.Buffer().write(encoded)
        source.skip(12)
        while (true) {
            val id = source.readByte().toInt() and 0xff
            val length = source.readIntLe()
            source.skip(length.toLong())
            if (id == HeaderFieldId.EndOfHeader.ordinal) {
                return encoded.size - source.size.toInt()
            }
        }
    }

    private enum class DecoderApi {
        Database,
        BinaryVisitor,
    }
}

private class HeaderAuthenticationSource(encoded: ByteArray) : RawSource {
    private val source = Buffer().apply { write(encoded) }
    var closeCount = 0
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        source.readAtMostTo(sink, minOf(byteCount, 17L))

    override fun close() {
        closeCount++
        source.close()
    }
}
