package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.FormatError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okio.ByteString.Companion.EMPTY

class BaseKdfProviderSpec {
    @Test
    fun kdfValidationSpec() = runKotpassSpec {

    describe("KDF work-factor validation") {
        val compositeKey = ByteArray(32)

        fun argon2(
            parallelism: UInt = 2U,
            memory: ULong = 32UL * 1024UL * 1024UL,
            iterations: ULong = 8UL
        ) = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2id,
            salt = EMPTY,
            parallelism = parallelism,
            memory = memory,
            iterations = iterations,
            version = 0x13U,
            secretKey = null,
            associatedData = null
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
                    compositeKey
                )
            }
        }
        it("Rejects excessive AES-KDF rounds") {
            assertFailsWith<FormatError.InvalidHeader> {
                BaseKdfProvider.transformKey(
                    KdfParameters.Aes(rounds = 1_000_000_000UL, seed = EMPTY),
                    compositeKey
                )
            }
        }
    }
    }
}
