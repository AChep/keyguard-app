package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Meta
import kotlinx.io.Buffer
import kotlinx.io.Source
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class KeePassDatabaseWriterTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("writer-password"))

    // Spans several stream buffers.
    private val content = ByteArray(200 * 1024 + 7) { (it * 31).toByte() }

    @Test
    fun binariesRoundTripAfterTheirSourcesAreReleased() {
        DatabaseHeader.Compression.entries.forEach { compression ->
            var released = false
            val streamed = BinaryData.Streamed.create(memoryProtection = false) {
                check(!released) { "The source is released." }
                content.toSource()
            }
            val uncompressed = BinaryData.Uncompressed(memoryProtection = false, rawContent = byteArrayOf(1, 2, 3))
            val database = databaseVer4x(compression)

            val encoded = Buffer()
            database.openWriter(encoded).use { writer ->
                writer.writeBinary(streamed)
                released = true
                writer.writeBinary(uncompressed)
                writer.finish(database.contentWith(streamed, uncompressed))
            }

            val decoded = KeePassDatabase.decode(encoded, credentials)
            val references = decoded.content.group.entries.single().binaries
            assertEquals(listOf("0.bin", "1.bin"), references.map { it.name })
            assertContentEquals(content, decoded.binaries.getValue(references[0].hash).getContent())
            assertContentEquals(byteArrayOf(1, 2, 3), decoded.binaries.getValue(references[1].hash).getContent())
        }
    }

    @Test
    fun binaryWithTheSameHashIsWrittenOnce() {
        val binary = BinaryData.Uncompressed(memoryProtection = false, rawContent = content)
        val copy = BinaryData.Uncompressed(memoryProtection = false, rawContent = content.copyOf())
        val database = databaseVer4x()

        val encoded = Buffer()
        database.openWriter(encoded).use { writer ->
            writer.writeBinary(binary)
            writer.writeBinary(copy)
            writer.finish(database.contentWith(binary, copy))
        }

        val decoded = KeePassDatabase.decode(encoded, credentials)
        assertEquals(1, decoded.binaries.size)
        assertEquals(2, decoded.content.group.entries.single().binaries.size)
    }

    @Test
    fun binariesOfTheDatabaseAreWrittenFirst() {
        val existing = BinaryData.Uncompressed(memoryProtection = false, rawContent = byteArrayOf(1))
        val added = BinaryData.Uncompressed(memoryProtection = false, rawContent = byteArrayOf(2))
        val database = databaseVer4x().let { database ->
            database.copy(
                innerHeader = database.innerHeader.copy(
                    binaries = linkedMapOf(existing.hash to existing),
                ),
            )
        }

        val encoded = Buffer()
        database.openWriter(encoded).use { writer ->
            writer.writeBinary(added)
            writer.finish(database.contentWith(existing, added))
        }

        val decoded = KeePassDatabase.decode(encoded, credentials)
        assertEquals(
            listOf(1.toByte(), 2.toByte()),
            decoded.binaries.values.map { it.getContent().single() },
        )
    }

    @Test
    fun finishFailsWhenTheContentReferencesAnUnwrittenBinary() {
        val binary = BinaryData.Uncompressed(memoryProtection = false, rawContent = byteArrayOf(1))
        val database = databaseVer4x()

        database.openWriter(Buffer()).use { writer ->
            assertFailsWith<FormatError.InvalidContent> {
                writer.finish(database.contentWith(binary))
            }
        }
    }

    @Test
    fun writerRejectsWritesAfterAFailure() {
        var calls = 0
        val streamed = BinaryData.Streamed.create(memoryProtection = false) {
            // The first read computes the size, the second one is shorter.
            val bytes = if (calls++ == 0) content else content.copyOf(content.size - 1)
            bytes.toSource()
        }
        val database = databaseVer4x()

        database.openWriter(Buffer()).use { writer ->
            assertFailsWith<IllegalStateException> {
                writer.writeBinary(streamed)
            }
            val failure = assertFailsWith<IllegalStateException> {
                writer.finish(database.content)
            }
            assertEquals("KeePass database writer has failed", failure.message)
        }
    }

    private fun KeePassDatabase.contentWith(
        vararg binaries: BinaryData,
    ): DatabaseContent {
        val entry = Entry(
            uuid = Uuid.random(),
            binaries = binaries.mapIndexed { index, binary ->
                BinaryReference(hash = binary.hash, name = "$index.bin")
            },
        )
        return content.copy(
            group = content.group.copy(entries = content.group.entries + entry),
        )
    }

    private fun databaseVer4x(
        compression: DatabaseHeader.Compression = DatabaseHeader.Compression.GZip,
    ): KeePassDatabase.Ver4x =
        KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let { database ->
            database.copy(
                header = database.header.copy(
                    compression = compression,
                    kdfParameters = KdfParameters.Aes(
                        rounds = 1U,
                        seed = ByteArray(32) { it.toByte() }.toByteString(),
                    ),
                ),
            )
        }

    private fun ByteArray.toSource(): Source = Buffer().apply { write(this@toSource) }
}
