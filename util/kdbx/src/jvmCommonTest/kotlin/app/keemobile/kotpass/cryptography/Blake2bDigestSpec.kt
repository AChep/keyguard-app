package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.resources.Blake2bDigestRes
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import org.bouncycastle.crypto.digests.Blake2bDigest

class Blake2bDigestSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Blake2b digest") {
        it("Properly hashes input") {
            Blake2bDigestRes.TestCases.forEach {
                val output = ByteArray(it.outputLength)
                Blake2bDigest(
                    it.key,
                    it.outputLength,
                    it.salt,
                    it.personalization,
                ).apply {
                    update(it.input, 0, it.input.size)
                    doFinal(output, 0)
                }

                output shouldBe it.output
            }
        }
    }
    }
}
