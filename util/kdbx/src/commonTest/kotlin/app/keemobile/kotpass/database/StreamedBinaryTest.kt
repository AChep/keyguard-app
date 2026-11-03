package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Meta
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class StreamedBinaryTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("streamed-binary-password"))

    // Spans several stream buffers.
    private val content = ByteArray(200 * 1024 + 7) { (it * 31).toByte() }

    @Test
    fun hashMatchesUncompressedBinaryWithTheSameContent() {
        val streamed = BinaryData.Streamed.create(memoryProtection = false) { content.toSource() }
        val uncompressed = BinaryData.Uncompressed(memoryProtection = false, rawContent = content)

        assertEquals(uncompressed.hash, streamed.hash)
        assertEquals(content.size.toLong(), streamed.size)
    }

    @Test
    fun builderHashesContentWrittenThroughItsSink() {
        listOf(false, true).forEach { memoryProtection ->
            val output = Buffer()
            val streamed = BinaryData.Streamed.Builder(memoryProtection).use { builder ->
                builder.sink(output).use { sink ->
                    sink.write(content)
                }
                builder.build { content.toSource() }
            }
            val uncompressed = BinaryData.Uncompressed(memoryProtection, rawContent = content)

            assertEquals(uncompressed.hash, streamed.hash)
            assertEquals(content.size.toLong(), streamed.size)
            assertContentEquals(content, output.readByteArray())
        }
    }

    @Test
    fun kdbx4RoundTripsStreamedContent() {
        val streamed = BinaryData.Streamed.create(memoryProtection = false) { content.toSource() }

        val decoded = roundTrip(databaseVer4x(), streamed)

        val reference = decoded.content.group.entries.single().binaries.single()
        assertEquals("file.bin", reference.name)
        assertContentEquals(content, decoded.binaries.getValue(reference.hash).getContent())
    }

    @Test
    fun kdbx3RoundTripsStreamedContent() {
        val streamed = BinaryData.Streamed.create(memoryProtection = false) { content.toSource() }

        val decoded = roundTrip(databaseVer3x(), streamed)

        val reference = decoded.content.group.entries.single().binaries.single()
        assertContentEquals(content, decoded.binaries.getValue(reference.hash).getContent())
    }

    @Test
    fun encodingFailsWhenStreamedContentChangesSize() {
        var calls = 0
        val streamed = BinaryData.Streamed.create(memoryProtection = false) {
            // The first read computes the size, the second one is shorter.
            val bytes = if (calls++ == 0) content else content.copyOf(content.size - 1)
            bytes.toSource()
        }
        val database = databaseVer4x().withBinary(streamed)

        assertFailsWith<IllegalStateException> {
            database.encodeTo(Buffer())
        }
    }

    private fun roundTrip(
        database: KeePassDatabase,
        binary: BinaryData,
    ): KeePassDatabase {
        val encoded = Buffer()
        database.withBinary(binary).encodeTo(encoded)
        return KeePassDatabase.decode(encoded, credentials)
    }

    private fun KeePassDatabase.withBinary(
        binary: BinaryData,
    ): KeePassDatabase {
        val entry = Entry(
            uuid = Uuid.random(),
            binaries = listOf(BinaryReference(hash = binary.hash, name = "file.bin")),
        )
        return modifyContent {
            copy(group = group.copy(entries = group.entries + entry))
        }.modifyBinaries { it + (binary.hash to binary) }
    }

    private fun databaseVer4x(): KeePassDatabase =
        KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let { database ->
            database.copy(
                header = database.header.copy(
                    kdfParameters = KdfParameters.Aes(
                        rounds = 1U,
                        seed = ByteArray(32) { it.toByte() }.toByteString(),
                    ),
                ),
            )
        }

    private fun databaseVer3x(): KeePassDatabase =
        KeePassDatabase.Ver3x.create("Root", Meta(), credentials).let { database ->
            database.copy(
                header = database.header.copy(transformRounds = 1U),
            )
        }

    private fun ByteArray.toSource(): Source = Buffer().apply { write(this@toSource) }
}
