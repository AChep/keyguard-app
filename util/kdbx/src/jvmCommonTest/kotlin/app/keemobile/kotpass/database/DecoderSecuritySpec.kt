package app.keemobile.kotpass.database

import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.errors.FormatError
import okio.Buffer
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class DecoderSecuritySpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Decoder hardening") {
        it("Rejects a KDBX3 file whose EncryptionIV length does not match the cipher") {
            // ver3_aes.kdbx uses AES, which expects a 16-byte IV. Truncate it.
            val tampered = withModifiedV3Header(loadRawBytes("ver3_aes.kdbx")) { fields ->
                truncateField(fields, EncryptionIvFieldId, size = 4)
            }

            assertFailsWith<FormatError.InvalidHeader> {
                KeePassDatabase.decode(
                    inputStream = ByteArrayInputStream(tampered),
                    credentials = Credentials.from(EncryptedValue.fromString("1"))
                )
            }
        }

        it("Rejects a short IV for a stream cipher instead of throwing a raw out-of-bounds exception") {
            // Point the cipher at ChaCha20 (expects a 12-byte IV) and give it a
            // 4-byte IV. Before the fix this reached ChaCha7539Engine.setKey and
            // threw an uncaught ArrayIndexOutOfBoundsException, pre-authentication.
            val tampered = withModifiedV3Header(loadRawBytes("ver3_aes.kdbx")) { fields ->
                replaceField(fields, CipherIdFieldId, ChaCha20Uuid.toByteArray())
                truncateField(fields, EncryptionIvFieldId, size = 4)
            }

            assertFailsWith<FormatError.InvalidHeader> {
                KeePassDatabase.decode(
                    inputStream = ByteArrayInputStream(tampered),
                    credentials = Credentials.from(EncryptedValue.fromString("1"))
                )
            }
        }
    }
    }
}

// HeaderFieldId ordinals (see constants/HeaderFieldId.kt).
private const val EndOfHeaderFieldId = 0
private const val CipherIdFieldId = 2
private const val EncryptionIvFieldId = 7

private val ChaCha20Uuid = Uuid.parse("d6038a2b-8b6f-4cb5-a524-339a31dbb59a")

private fun loadRawBytes(fileName: String): ByteArray = ClassLoader
    .getSystemResourceAsStream(fileName)!!
    .use { it.readBytes() }

/**
 * Parses the fields of a KDBX3 header (`[id:1][len:2 LE][data]`), lets [modify]
 * rewrite them, then re-serializes the header followed by the untouched
 * encrypted body.
 */
private fun withModifiedV3Header(
    original: ByteArray,
    modify: (MutableList<Pair<Int, ByteArray>>) -> Unit
): ByteArray {
    val source = Buffer().write(original)
    val out = Buffer()

    // Base + secondary signature (8 bytes) and format version (4 bytes).
    out.write(source.readByteArray(12))

    val fields = mutableListOf<Pair<Int, ByteArray>>()
    while (true) {
        val id = source.readByte().toInt()
        val length = source.readShortLe().toInt() and 0xFFFF
        val data = source.readByteArray(length.toLong())
        fields += id to data
        if (id == EndOfHeaderFieldId) break
    }

    modify(fields)

    for ((id, data) in fields) {
        out.writeByte(id)
        out.writeShortLe(data.size)
        out.write(data)
    }
    // Everything after the header is the encrypted content; copy it verbatim.
    out.writeAll(source)
    return out.readByteArray()
}

private fun truncateField(
    fields: MutableList<Pair<Int, ByteArray>>,
    fieldId: Int,
    size: Int
) {
    val index = fields.indexOfFirst { it.first == fieldId }
    require(index >= 0) { "Field $fieldId not present in header." }
    fields[index] = fieldId to fields[index].second.copyOf(size)
}

private fun replaceField(
    fields: MutableList<Pair<Int, ByteArray>>,
    fieldId: Int,
    data: ByteArray
) {
    val index = fields.indexOfFirst { it.first == fieldId }
    require(index >= 0) { "Field $fieldId not present in header." }
    fields[index] = fieldId to data
}
