package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.extensions.bufferStream
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.errors.FormatError
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

        it("Consumes and skips an unknown outer-header field") {
            val original = DatabaseHeader.Ver4x.create()
            val encoded = Buffer().apply(original::writeTo)
            val modified = Buffer().apply {
                write(encoded, 12) // Signatures and version
                writeByte(0x7F)
                writeIntLe(3)
                writeUtf8("ext")
                writeAll(encoded)
            }

            val decoded = DatabaseHeader.readFrom(modified.asBufferedStream())

            decoded.version shouldBe original.version
            decoded.cipherId shouldBe original.cipherId
            decoded.compression shouldBe original.compression
            decoded.masterSeed shouldBe original.masterSeed
            decoded.encryptionIV shouldBe original.encryptionIV
        }

        it("Rejects a negative KDBX4 outer-header field length") {
            val encoded = Buffer().apply(DatabaseHeader.Ver4x.create()::writeTo)
            val modified = Buffer().apply {
                write(encoded, 12) // Signatures and version
                writeByte(1) // Comment
                writeIntLe(-1)
                writeAll(encoded)
            }

            assertFailsWith<FormatError.InvalidHeader> {
                DatabaseHeader.readFrom(modified.asBufferedStream())
            }
        }
    }
    }
}

private fun Buffer.asBufferedStream() = inputStream().source().bufferStream()

private fun decodeHeader(fileName: String) = ClassLoader
    .getSystemResourceAsStream(fileName)!!
    .use { DatabaseHeader.readFrom(it.source().bufferStream()) }
