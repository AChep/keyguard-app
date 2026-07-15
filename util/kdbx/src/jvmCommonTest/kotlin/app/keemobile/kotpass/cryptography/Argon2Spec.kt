package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.Argon2Res
import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode
import com.artemchep.keyguard.nativecrypto.NativeArgon2Version
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test

class Argon2Spec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {
        describe("Argon2") {
            val cases = listOf(
                Case(NativeArgon2Mode.ARGON2_D, NativeArgon2Version.VERSION_1_0, "96a9d4e5a1734092c85e29f410a45914a5dd1f5cbf08b2670da68a0285abf32b"),
                Case(NativeArgon2Mode.ARGON2_I, NativeArgon2Version.VERSION_1_0, "87aeedd6517ab830cd9765cd8231abb2e647a5dee08f7c05e02fcb763335d0fd"),
                Case(NativeArgon2Mode.ARGON2_ID, NativeArgon2Version.VERSION_1_0, "b64615f07789b66b645b67ee9ed3b377ae350b6bfcbb0fc95141ea8f322613c0"),
                Case(NativeArgon2Mode.ARGON2_D, NativeArgon2Version.VERSION_1_3, "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb"),
                Case(NativeArgon2Mode.ARGON2_I, NativeArgon2Version.VERSION_1_3, "c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8"),
                Case(NativeArgon2Mode.ARGON2_ID, NativeArgon2Version.VERSION_1_3, "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"),
            )

            cases.forEach { case ->
                it("matches the reference vector for ${case.mode} ${case.version}") {
                    deriveNative(case) shouldBe case.expected.decodeHexToArray()
                }

                it("matches the permanent BC test oracle for ${case.mode} ${case.version}") {
                    deriveNative(case) shouldBe deriveWithBouncyCastle(case)
                }
            }
        }
    }

    private fun deriveNative(case: Case): ByteArray = NativeCryptoPrimitives.argon2(
        mode = case.mode,
        version = case.version,
        seed = Argon2Res.TestPassword,
        salt = Argon2Res.TestSalt,
        secret = Argon2Res.TestSecret,
        associatedData = Argon2Res.TestAdditional,
        iterations = 3,
        parallelism = 4,
        memoryKb = 32,
        length = 32,
    )

    private fun deriveWithBouncyCastle(case: Case): ByteArray {
        val type = when (case.mode) {
            NativeArgon2Mode.ARGON2_D -> Argon2Parameters.ARGON2_d
            NativeArgon2Mode.ARGON2_I -> Argon2Parameters.ARGON2_i
            NativeArgon2Mode.ARGON2_ID -> Argon2Parameters.ARGON2_id
        }
        val version = when (case.version) {
            NativeArgon2Version.VERSION_1_0 -> Argon2Parameters.ARGON2_VERSION_10
            NativeArgon2Version.VERSION_1_3 -> Argon2Parameters.ARGON2_VERSION_13
        }
        val parameters = Argon2Parameters.Builder(type)
            .withVersion(version)
            .withSalt(Argon2Res.TestSalt)
            .withSecret(Argon2Res.TestSecret)
            .withAdditional(Argon2Res.TestAdditional)
            .withIterations(3)
            .withParallelism(4)
            .withMemoryAsKB(32)
            .build()
        return ByteArray(32).also { output ->
            Argon2BytesGenerator().apply { init(parameters) }
                .generateBytes(Argon2Res.TestPassword, output)
        }
    }

    private data class Case(
        val mode: NativeArgon2Mode,
        val version: NativeArgon2Version,
        val expected: String,
    )
}
