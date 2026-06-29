package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.cryptography.engines.ChaChaEngine
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.ChaChaRes
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class ChaChaSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("ChaCha stream cipher") {
        it("Properly outputs key stream") {
            ChaChaRes.ChaChaTestCases.forEach { testCase ->
                val engine = ChaChaEngine(testCase.rounds).apply {
                    init(
                        key = testCase.key.decodeHexToArray(),
                        iv = testCase.iv.decodeHexToArray()
                    )
                }
                val expectedOutput = testCase.output.decodeHexToArray()
                engine.getBytes(expectedOutput.size) shouldBe expectedOutput
            }
        }
    }
    }
}
