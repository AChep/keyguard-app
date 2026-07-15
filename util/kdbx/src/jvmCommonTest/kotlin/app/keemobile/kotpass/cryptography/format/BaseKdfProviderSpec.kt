package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.Argon2Res
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okio.ByteString.Companion.EMPTY
import okio.ByteString.Companion.toByteString

class BaseKdfProviderSpec {
    @Test
    fun kdfValidationSpec() = runKotpassSpec {
        describe("KDF work-factor validation") {
            val compositeKey = ByteArray(32)

            fun argon2(
                parallelism: UInt = 2U,
                memory: ULong = 32UL * 1024UL * 1024UL,
                iterations: ULong = 8UL,
            ) = KdfParameters.Argon2(
                variant = KdfParameters.Argon2.Variant.Argon2id,
                salt = EMPTY,
                parallelism = parallelism,
                memory = memory,
                iterations = iterations,
                version = 0x13U,
                secretKey = null,
                associatedData = null,
            )

            it("Rejects Argon2 parallelism of zero (division-by-zero guard)") {
                assertFailsWith<FormatError.InvalidHeader> {
                    BaseKdfProvider.transformKey(argon2(parallelism = 0U), compositeKey)
                }
            }
            it("Rejects excessive Argon2 iterations") {
                assertFailsWith<FormatError.InvalidHeader> {
                    BaseKdfProvider.transformKey(argon2(iterations = 1_000_000UL), compositeKey)
                }
            }
            it("Rejects excessive Argon2 memory") {
                assertFailsWith<FormatError.InvalidHeader> {
                    BaseKdfProvider.transformKey(
                        argon2(memory = 8UL * 1024UL * 1024UL * 1024UL),
                        compositeKey,
                    )
                }
            }
            it("Rejects excessive AES-KDF rounds") {
                assertFailsWith<FormatError.InvalidHeader> {
                    BaseKdfProvider.transformKey(
                        KdfParameters.Aes(rounds = 1_000_000_000UL, seed = EMPTY),
                        compositeKey,
                    )
                }
            }
        }

        describe("Argon2 compatibility forwarding") {
            it("maps every non-1.3 header version to 1.0 and forwards secret and associated data") {
                val parameters = KdfParameters.Argon2(
                    variant = KdfParameters.Argon2.Variant.Argon2id,
                    salt = Argon2Res.TestSalt.toByteString(),
                    parallelism = 4U,
                    memory = 32UL * 1024UL,
                    iterations = 3UL,
                    version = 0x11U,
                    secretKey = Argon2Res.TestSecret.toByteString(),
                    associatedData = Argon2Res.TestAdditional.toByteString(),
                )

                BaseKdfProvider.transformKey(parameters, Argon2Res.TestPassword) shouldBe
                    "b64615f07789b66b645b67ee9ed3b377ae350b6bfcbb0fc95141ea8f322613c0"
                        .decodeHexToArray()
            }
        }
    }
}
