package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.nextByteString
import app.keemobile.kotpass.models.BinaryData
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import app.keemobile.kotpass.cryptography.SecureRandom

private object InnerHeaderFieldId {
    const val Terminator = 0x00
    const val StreamId = 0x01
    const val StreamKey = 0x02
    const val Binary = 0x03
}

private const val BinaryFlagsSize = 1

data class DatabaseInnerHeader(
    val randomStreamId: CrsAlgorithm,
    val randomStreamKey: ByteString,
    @PublishedApi
    internal val binaries: Map<ByteString, BinaryData> = linkedMapOf()
) {
    internal fun writeTo(sink: BufferedSink) = with(sink) {
        writeByte(InnerHeaderFieldId.StreamId)
        writeIntLe(Int.SIZE_BYTES)
        writeIntLe(randomStreamId.ordinal)

        writeByte(InnerHeaderFieldId.StreamKey)
        writeIntLe(randomStreamKey.size)
        write(randomStreamKey)

        for ((_, binary) in binaries) {
            val data = binary.getContent()
            writeByte(InnerHeaderFieldId.Binary)
            writeIntLe(data.size + BinaryFlagsSize)
            writeByte(if (binary.memoryProtection) 0x1 else 0x0)
            write(data)
        }

        writeByte(InnerHeaderFieldId.Terminator)
        writeIntLe(0)
    }

    companion object {
        fun create(random: SecureRandom = SecureRandom()) = DatabaseInnerHeader(
            randomStreamId = CrsAlgorithm.ChaCha20,
            randomStreamKey = random.nextByteString(64),
            binaries = linkedMapOf()
        )

        internal fun readFrom(source: BufferedSource): DatabaseInnerHeader {
            val binaries = linkedMapOf<ByteString, BinaryData>()
            var randomStreamId: CrsAlgorithm? = null
            var randomStreamKey: ByteString? = null

            while (true) {
                val id = source.readByte()
                val length = source.readIntLe().toLong()

                when (id.toInt()) {
                    InnerHeaderFieldId.Terminator -> {
                        source.readByteArray(length)
                        break
                    }
                    InnerHeaderFieldId.StreamId -> {
                        randomStreamId = CrsAlgorithm.entries[source.readIntLe()]
                    }
                    InnerHeaderFieldId.StreamKey -> {
                        randomStreamKey = source.readByteString(length)
                    }
                    InnerHeaderFieldId.Binary -> {
                        val memoryProtection = source.readByte() != 0x0.toByte()
                        val content = source.readByteArray(length - BinaryFlagsSize)
                        val binary = BinaryData.Uncompressed(memoryProtection, content)
                        binaries[binary.hash] = binary
                    }
                    else -> throw FormatError.InvalidContent("Unknown inner header id: $id.")
                }
            }

            return DatabaseInnerHeader(
                randomStreamId = randomStreamId
                    ?: throw FormatError.InvalidContent("No random stream id found in inner header"),
                randomStreamKey = randomStreamKey
                    ?: throw FormatError.InvalidContent("No random stream key found in inner header"),
                binaries = binaries
            )
        }
    }
}
