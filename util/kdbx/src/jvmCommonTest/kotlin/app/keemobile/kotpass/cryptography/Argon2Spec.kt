package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.cryptography.engines.Argon2Engine
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.Argon2Res
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class Argon2Spec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Argon2") {
        it("Type D") {
            val expected = "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb"
            val result = ByteArray(32)

            Argon2Engine(
                variant = Argon2Engine.Variant.Argon2d,
                version = Argon2Engine.Version.Ver13,
                salt = Argon2Res.TestSalt,
                secret = Argon2Res.TestSecret,
                additional = Argon2Res.TestAdditional,
                iterations = 3,
                parallelism = 4,
                memory = 32
            ).generateBytes(Argon2Res.TestPassword, result)

            result shouldBe expected.decodeHexToArray()
        }

        it("Type I") {
            val expected = "c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8"
            val result = ByteArray(32)

            Argon2Engine(
                variant = Argon2Engine.Variant.Argon2i,
                version = Argon2Engine.Version.Ver13,
                salt = Argon2Res.TestSalt,
                secret = Argon2Res.TestSecret,
                additional = Argon2Res.TestAdditional,
                iterations = 3,
                parallelism = 4,
                memory = 32
            ).generateBytes(Argon2Res.TestPassword, result)

            result shouldBe expected.decodeHexToArray()
        }

        it("Type ID") {
            val expected = "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
            val result = ByteArray(32)

            Argon2Engine(
                variant = Argon2Engine.Variant.Argon2id,
                version = Argon2Engine.Version.Ver13,
                salt = Argon2Res.TestSalt,
                secret = Argon2Res.TestSecret,
                additional = Argon2Res.TestAdditional,
                iterations = 3,
                parallelism = 4,
                memory = 32
            ).generateBytes(Argon2Res.TestPassword, result)

            result shouldBe expected.decodeHexToArray()
        }
    }
    }
}
