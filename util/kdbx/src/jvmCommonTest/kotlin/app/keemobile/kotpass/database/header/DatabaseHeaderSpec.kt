package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.extensions.bufferStream
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldBeInstanceOf
import okio.Buffer
import okio.source

class DatabaseHeaderSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Database header") {
        it("Properly reads KDF parameters") {
            val ver4Argon2 = decodeHeader("ver4_argon2.kdbx")
            ver4Argon2.signature.base shouldBe Signature.Base
            ver4Argon2.shouldBeInstanceOf<DatabaseHeader.Ver4x>()
            with(ver4Argon2) {
                kdfParameters.shouldBeInstanceOf<KdfParameters.Argon2>()
            }

            val ver4Aes = decodeHeader("ver4_aes.kdbx")
            ver4Aes.shouldBeInstanceOf<DatabaseHeader.Ver4x>()
            with(ver4Aes) {
                kdfParameters.shouldBeInstanceOf<KdfParameters.Aes>()
            }

            val ver3Aes = decodeHeader("ver3_aes.kdbx")
            ver3Aes.shouldBeInstanceOf<DatabaseHeader.Ver3x>()
        }

        it("Try to read/write header") {
            val ver4Argon2 = decodeHeader("ver4_argon2.kdbx").let { header ->
                val buffer = Buffer()
                header.writeTo(buffer)
                buffer.snapshot()
                    .toByteArray()
                    .inputStream()
                    .source()
                    .bufferStream()
            }.let(DatabaseHeader.Companion::readFrom)

            ver4Argon2.signature.base shouldBe Signature.Base
            ver4Argon2.shouldBeInstanceOf<DatabaseHeader.Ver4x>()
            with(ver4Argon2) {
                kdfParameters.shouldBeInstanceOf<KdfParameters.Argon2>()
            }
        }
    }
    }
}

private fun decodeHeader(fileName: String) = ClassLoader
    .getSystemResourceAsStream(fileName)!!
    .use { DatabaseHeader.readFrom(it.source().bufferStream()) }
