package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.KdfConst
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.VariantDictionaryRes
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.errors.FormatError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldBeInstanceOf
import okio.ByteString.Companion.toByteString
import okio.Buffer
import java.io.InputStream

class VariantDictionarySpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Variant dictionary") {
        it("Properly reads and writes data") {
            val dictionary = ClassLoader
                .getSystemResourceAsStream("kdf_params")!!
                .use(InputStream::readAllBytes)
                .toByteString()
                .let(VariantDictionary::readFrom)
                .let(VariantDictionary::writeToByteString)
                .let(VariantDictionary::readFrom)
            val uuid = dictionary[KdfConst.Keys.Uuid]

            uuid.shouldBeInstanceOf<VariantItem.Bytes>()
            uuid.value.toByteArray() shouldBe VariantDictionaryRes.Uuid.decodeHexToArray()
        }

        it("Rejects an unknown item type instead of treating its payload as a terminator") {
            val dictionary = Buffer().apply {
                writeShortLe(0x0100)
                writeByte(0x7F) // Unknown type
                writeIntLe(1)
                writeUtf8("x")
                writeIntLe(1)
                writeByte(0) // Previously consumed as the dictionary terminator
                writeByte(0)
            }.readByteString()

            assertFailsWith<FormatError.InvalidHeader> {
                VariantDictionary.readFrom(dictionary)
            }
        }

        it("Rejects a critical high-bit dictionary version") {
            val dictionary = Buffer().apply {
                writeShortLe(0x8000)
                writeByte(0)
            }.readByteString()

            assertFailsWith<FormatError.InvalidHeader> {
                VariantDictionary.readFrom(dictionary)
            }
        }
    }
    }
}
