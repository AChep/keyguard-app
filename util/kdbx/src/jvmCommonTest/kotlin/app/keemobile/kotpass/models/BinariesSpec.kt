package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.database.modifiers.removeUnusedBinaries
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.parseAsXml
import com.artemchep.keyguard.util.foundation.crypto.sha256
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.xml.marshal
import app.keemobile.kotpass.xml.unmarshalBinaries
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import okio.buffer
import okio.ByteString.Companion.toByteString
import okio.source
import kotlin.uuid.Uuid

private const val Contents = "hello kotpass"
private const val ContentsAsXml =
    """<Binaries><Binary ID="0" Compressed="true">H4sIAAAAAAAAAMtIzcnJV8jOLylILC4GACxwuZMNAAAA</Binary></Binaries>"""

class BinariesSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Binaries") {
        it("Binary serialization does not affect compression") {
            BinaryData
                .Uncompressed(false, Contents.toByteArray())
                .marshal(0)
                .getText() shouldBe Contents.toByteArray().encodeBase64()
        }

        it("Binaries are properly decompressed") {
            unmarshalBinaries(ContentsAsXml.parseAsXml())
                .map { (_, binary) -> binary }
                .first()
                .getContent() shouldBe Contents.toByteArray()
        }

        it("Reads from compressed stream") {
            val binaryData = unmarshalBinaries(ContentsAsXml.parseAsXml())
                .map { (_, binary) -> binary }
                .first()

            binaryData
                .inputStream()
                .use { stream ->
                    val content = Contents.take(5)
                    val source = stream.source().buffer()
                    val sample = source
                        .use { it.readUtf8(content.length.toLong()) }

                    sample shouldBe content
                }
        }

        it("Indexes binaries by ref and hash") {
            val first = BinaryData.Uncompressed(false, "first".toByteArray())
            val second = BinaryData.Uncompressed(false, "second".toByteArray())
            val index = BinaryIndex(
                linkedMapOf(
                    first.hash to first,
                    second.hash to second,
                )
            )

            index.hashByRef(0) shouldBe first.hash
            index.hashByRef(1) shouldBe second.hash
            index.refByHash(second.hash) shouldBe 1
            index.getByRef(0)?.data?.getContent() shouldBe "first".toByteArray()
        }

        it("Finds compressed binaries by content sha256") {
            val binaryData = unmarshalBinaries(ContentsAsXml.parseAsXml())
                .map { (_, binary) -> binary }
                .first()
            val index = BinaryIndex(
                linkedMapOf(
                    binaryData.hash to binaryData,
                )
            )

            val contentHash = Contents
                .toByteArray()
                .let(::sha256)
                .toByteString()
            index.findByContentSha256(contentHash)
                ?.data
                ?.getContent() shouldBe Contents.toByteArray()
        }

        it("Removes unused binaries") {
            val database = KeePassDatabase.decode(
                ClassLoader.getSystemResourceAsStream("ver4_with_binaries.kdbx")!!,
                Credentials.from(EncryptedValue.fromString("1"))
            ).modifyEntry(Uuid.parse("6d9b7812-6d1a-1765-9cd7-c66a93a220e9")) {
                copy(binaries = listOf())
            }.removeUnusedBinaries()

            database.binaries.size shouldBe 0
        }
    }
    }
}
