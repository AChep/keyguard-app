package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
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
    }
    }
}
