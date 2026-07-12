package app.keemobile.kotpass.io

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.errors.FormatError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GzipSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Gzip") {
        it("Round-trips data within the size cap") {
            val original = ByteArray(4 * 1024 * 1024) { (it and 0xFF).toByte() }
            val restored = original.gzip().gunzip(maxSize = 8L * 1024 * 1024)

            restored.size shouldBe original.size
            restored.contentEquals(original) shouldBe true
        }

        it("Rejects a decompression bomb that exceeds the cap") {
            // 4 MiB of zeros compresses to a few KiB but inflates back to 4 MiB;
            // a 1 MiB cap must reject it rather than allocating the full output.
            val bomb = ByteArray(4 * 1024 * 1024).gzip()
            // The compressed input is well under the 1 MiB cap it will breach.
            (bomb.size < 1 * 1024 * 1024) shouldBe true

            assertFailsWith<FormatError.FailedCompression> {
                bomb.gunzip(maxSize = 1L * 1024 * 1024)
            }
        }
    }
    }
}
