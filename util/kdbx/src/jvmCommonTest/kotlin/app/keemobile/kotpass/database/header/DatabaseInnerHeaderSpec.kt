package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.errors.FormatError
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

            innerHeader.writeTo(buffer)
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
    }
    }
}
