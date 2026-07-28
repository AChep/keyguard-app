package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.database.BinaryWritePlan
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import kotlin.test.Test
import kotlin.test.assertFailsWith
import app.keemobile.kotpass.common.matchers.shouldBe
import okio.Buffer
import okio.buffer
import okio.source

class DatabaseInnerHeaderSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Database inner header") {
        it("Properly reads and writes data") {
            val buffer = Buffer()
            var innerHeader = ClassLoader
                .getSystemResourceAsStream("inner_header_with_binaries")!!
                .use { DatabaseInnerHeader.readFrom(it.source().buffer()) }

            innerHeader.randomStreamId shouldBe CrsAlgorithm.ChaCha20
            innerHeader.binaries.size shouldBe 2

            innerHeader.writeTo(buffer, BinaryWritePlan.create(innerHeader.binaries))
            innerHeader = DatabaseInnerHeader.readFrom(buffer)
            innerHeader.randomStreamId shouldBe CrsAlgorithm.ChaCha20
            innerHeader.binaries.size shouldBe 2
        }

        it("Rejects a binary field with zero length instead of underflowing") {
            val buffer = Buffer().apply {
                writeByte(0x03) // Binary
                writeIntLe(0) // length 0 => length - flags byte = -1
            }
            assertFailsWith<FormatError.InvalidContent> {
                DatabaseInnerHeader.readFrom(buffer)
            }
        }

        it("Rejects a field with a negative (high-bit) length") {
            val buffer = Buffer().apply {
                writeByte(0x02) // StreamKey
                writeIntLe(-1) // 0xFFFFFFFF => negative Long
            }
            assertFailsWith<FormatError.InvalidContent> {
                DatabaseInnerHeader.readFrom(buffer)
            }
        }

        it("Rejects an out-of-range inner random stream id") {
            val buffer = Buffer().apply {
                writeByte(0x01) // StreamId
                writeIntLe(Int.SIZE_BYTES)
                writeIntLe(999) // no such CrsAlgorithm ordinal
            }
            assertFailsWith<FormatError.InvalidContent> {
                DatabaseInnerHeader.readFrom(buffer)
            }
        }

        it("Normalizes duplicate KDBX4 binary positions when writing") {
            val content = "duplicate".encodeToByteArray()
            val buffer = Buffer().apply {
                writeByte(0x01) // StreamId
                writeIntLe(Int.SIZE_BYTES)
                writeIntLe(CrsAlgorithm.ChaCha20.ordinal)
                writeByte(0x02) // StreamKey
                writeIntLe(0)
                repeat(2) {
                    writeByte(0x03) // Binary
                    writeIntLe(content.size + 1)
                    writeByte(0)
                    write(content)
                }
                writeByte(0x00) // Terminator
                writeIntLe(0)
            }

            val innerHeader = DatabaseInnerHeader.readFrom(buffer)
            val expected = BinaryData.Uncompressed(false, content).hash

            BinaryIndex(innerHeader.binaries).hashByRef(1) shouldBe expected

            val encoded = Buffer()
            innerHeader.writeTo(encoded, BinaryWritePlan.create(innerHeader.binaries))
            val roundTripped = DatabaseInnerHeader.readFrom(encoded)

            roundTripped.binaries.size shouldBe 1
            BinaryIndex(roundTripped.binaries).hashByRef(0) shouldBe expected
        }

        it("Rejects a StreamId whose declared payload is not four bytes") {
            val buffer = Buffer().apply {
                writeByte(0x01) // StreamId
                writeIntLe(3)
                writeByte(CrsAlgorithm.ChaCha20.ordinal)
                writeByte(0)
                writeByte(0)
                writeByte(0x00) // Terminator
                writeIntLe(0)
            }

            assertFailsWith<FormatError.InvalidContent> {
                DatabaseInnerHeader.readFrom(buffer)
            }
        }

        it("Consumes and skips an unknown inner-header field") {
            val buffer = Buffer().apply {
                writeByte(0x7F)
                writeIntLe(3)
                writeUtf8("ext")
                writeByte(0x01) // StreamId
                writeIntLe(Int.SIZE_BYTES)
                writeIntLe(CrsAlgorithm.ChaCha20.ordinal)
                writeByte(0x02) // StreamKey
                writeIntLe(1)
                writeByte(42)
                writeByte(0x00) // Terminator
                writeIntLe(0)
            }

            val innerHeader = DatabaseInnerHeader.readFrom(buffer)

            innerHeader.randomStreamId shouldBe CrsAlgorithm.ChaCha20
            innerHeader.randomStreamKey.toByteArray() shouldBe byteArrayOf(42)
        }

        it("Uses only bit zero of the binary flags for memory protection") {
            val buffer = Buffer().apply {
                writeByte(0x01) // StreamId
                writeIntLe(Int.SIZE_BYTES)
                writeIntLe(CrsAlgorithm.ChaCha20.ordinal)
                writeByte(0x02) // StreamKey
                writeIntLe(0)
                writeByte(0x03) // Binary
                writeIntLe(2)
                writeByte(0x02) // Unknown flag, but not the protection bit
                writeByte(42)
                writeByte(0x00) // Terminator
                writeIntLe(0)
            }

            val binary = DatabaseInnerHeader.readFrom(buffer).binaries.values.single()

            binary.memoryProtection shouldBe false
        }
    }
    }
}
