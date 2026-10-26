package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.cryptography.SecureRandom
import app.keemobile.kotpass.database.BinaryPool
import app.keemobile.kotpass.database.BinaryWritePlan
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.nextByteString
import app.keemobile.kotpass.models.BinaryData
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString

internal object InnerHeaderFieldId {
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
    internal fun writeTo(
        sink: BufferedSink,
        binaryWritePlan: BinaryWritePlan,
    ) {
        writeFieldsTo(sink)
        for ((_, _, binary) in binaryWritePlan.entries) {
            writeBinaryTo(sink, binary)
        }
        writeTerminatorTo(sink)
    }

    /**
     * Writes the fields that come before the binaries.
     */
    internal fun writeFieldsTo(sink: BufferedSink) {
        with(sink) {
            writeByte(InnerHeaderFieldId.StreamId)
            writeIntLe(Int.SIZE_BYTES)
            writeIntLe(randomStreamId.ordinal)

            writeByte(InnerHeaderFieldId.StreamKey)
            writeIntLe(randomStreamKey.size)
            write(randomStreamKey)
        }
    }

    companion object {
        fun create(random: SecureRandom = SecureRandom()) = DatabaseInnerHeader(
            randomStreamId = CrsAlgorithm.ChaCha20,
            randomStreamKey = random.nextByteString(64),
            binaries = linkedMapOf()
        )

        internal fun writeBinaryTo(
            sink: BufferedSink,
            binary: BinaryData,
        ) {
            if (binary is BinaryData.Streamed) {
                // Copied from its source, so the content is
                // never held in memory as a whole.
                sink.writeBinaryHeader(binary.size, binary.memoryProtection)
                binary.writeContentTo(sink)
            } else {
                val data = binary.getContent()
                sink.writeBinaryHeader(data.size.toLong(), binary.memoryProtection)
                sink.write(data)
            }
        }

        internal fun writeTerminatorTo(sink: BufferedSink) {
            sink.writeByte(InnerHeaderFieldId.Terminator)
            sink.writeIntLe(0)
        }

        private fun BufferedSink.writeBinaryHeader(
            size: Long,
            memoryProtection: Boolean,
        ) {
            // The length is a signed little-endian int in the format.
            require(size <= Int.MAX_VALUE - BinaryFlagsSize) {
                "Binary content of $size bytes does not fit in the inner header."
            }
            writeByte(InnerHeaderFieldId.Binary)
            writeIntLe(size.toInt() + BinaryFlagsSize)
            writeByte(if (memoryProtection) 0x1 else 0x0)
        }

        internal fun readFrom(source: BufferedSource): DatabaseInnerHeader {
            val binaries = BinaryPool()
            var binaryRef = 0
            var randomStreamId: CrsAlgorithm? = null
            var randomStreamKey: ByteString? = null

            while (true) {
                val id = source.readByte().toUByte().toInt()
                // The length is a signed little-endian int in the format, so a
                // declared payload >= 2 GiB wraps to a negative value; guard it
                // before it reaches any read call.
                val length = source.readIntLe().toLong()
                if (length < 0) {
                    throw FormatError.InvalidContent("Invalid inner header field length: $length.")
                }
                val data = Buffer().write(source.readByteString(length))

                when (id) {
                    InnerHeaderFieldId.Terminator -> break
                    InnerHeaderFieldId.StreamId -> {
                        if (length != Int.SIZE_BYTES.toLong()) {
                            throw FormatError.InvalidContent(
                                "Invalid inner random stream id field length: $length."
                            )
                        }
                        val ordinal = data.readIntLe()
                        randomStreamId = CrsAlgorithm.entries.getOrNull(ordinal)
                            ?: throw FormatError.InvalidContent("Unknown inner random stream id: $ordinal.")
                    }
                    InnerHeaderFieldId.StreamKey -> {
                        randomStreamKey = data.readByteString()
                    }
                    InnerHeaderFieldId.Binary -> {
                        if (length < BinaryFlagsSize) {
                            val msg = "Invalid binary inner header field length: $length."
                            throw FormatError.InvalidContent(msg)
                        }

                        val flags = data.readByte().toInt()
                        val memoryProtection = flags and 0x01 != 0
                        val content = data.readByteArray()
                        val binary = BinaryData.Uncompressed(memoryProtection, content)
                        binaries.add(binaryRef++, binary)
                    }
                    else -> Unit
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
