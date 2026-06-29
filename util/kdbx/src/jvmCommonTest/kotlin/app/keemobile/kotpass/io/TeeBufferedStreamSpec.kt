package app.keemobile.kotpass.io

import app.keemobile.kotpass.extensions.teeBufferStream
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import okio.Buffer
import okio.ByteString
import okio.source
import java.io.ByteArrayInputStream

private val DummyData = "Lorem ipsum".toByteArray()

class TeeBufferedStreamSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("TeeBufferedStream") {
        it("Writes data to the side buffer") {
            val buffer = Buffer()
            val data = ByteString.of(*DummyData)
            val tee = ByteArrayInputStream(DummyData)
                .source()
                .teeBufferStream(buffer)

            tee.read(Buffer(), 5)
            buffer.snapshot() shouldBe data.substring(0, 5)

            tee.read(ByteArray(1), 0, 1)
            buffer.snapshot() shouldBe data.substring(0, 6)

            tee.readFully(ByteArray((data.size - buffer.size).toInt()))
            buffer.snapshot() shouldBe data
        }

        it("Does not write data to the side buffer while peeking") {
            val buffer = Buffer()
            val tee = ByteArrayInputStream(DummyData)
                .source()
                .teeBufferStream(buffer)
                .peek()

            tee.read(Buffer(), 5)
            buffer.snapshot().size shouldBe 0
        }
    }
    }
}
