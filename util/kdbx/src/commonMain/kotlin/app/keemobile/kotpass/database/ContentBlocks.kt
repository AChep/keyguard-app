package app.keemobile.kotpass.database

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.BufferedStream
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import com.artemchep.keyguard.util.foundation.crypto.createHmacSha256
import com.artemchep.keyguard.util.foundation.crypto.sha256
import com.artemchep.keyguard.util.foundation.crypto.sha512
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import kotlin.math.min

internal object ContentBlocks {
    private const val BlockSplitRate = 1048576

    private class Block(
        val index: Long,
        val length: Int,
        val data: ByteArray
    )

    fun readContentBlocksVer3x(source: BufferedSource): ByteArray {
        val contentData = Buffer()

        while (true) {
            val index = source.readIntLe()
            val hash = source.readByteArray(32)
            val length = source.readIntLe()

            if (length > 0) {
                val data = source.readByteArray(length.toLong())
                if (!sha256(data).contentEquals(hash)) {
                    throw FormatError.InvalidContent("Hash for block $index does not match.")
                }
                contentData.write(data)
            } else {
                break
            }
        }

        return contentData.readByteArray()
    }

    fun readContentBlocksVer4x(
        source: BufferedStream,
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ): ByteArray {
        val contentData = Buffer()
        val hmacKey = createBlockHmacKey(masterSeed, transformedKey)
        var index = 0L

        while (true) {
            val hash = source.readByteArray(32)
            val length = source.readIntLe()

            if (length > 0) {
                val data = source.readByteArray(length.toLong())
                if (!createBlockHmac(hmacKey, index, length, data).constantTimeEquals(hash)) {
                    throw FormatError.InvalidContent("HMAC for block $index does not match.")
                }
                contentData.write(data)
                index++
            } else {
                break
            }
        }

        return contentData.readByteArray()
    }

    internal fun writeContentBlocksVer3x(
        sink: BufferedSink,
        contentData: ByteArray
    ) = writeContentBlocks(sink, contentData, true) {
        if (data.isNotEmpty()) {
            sha256(data)
        } else {
            ByteArray(32) { 0x0 }
        }
    }

    internal fun writeContentBlocksVer4x(
        sink: BufferedSink,
        contentData: ByteArray,
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) {
        val hmacKey = createBlockHmacKey(masterSeed, transformedKey)

        writeContentBlocks(sink, contentData, false) {
            createBlockHmac(hmacKey, index, length, data)
        }
    }

    private fun writeContentBlocks(
        sink: BufferedSink,
        contentData: ByteArray,
        writeIndexes: Boolean,
        hashFunc: Block.() -> ByteArray
    ): Unit = with(sink) {
        var index = 0L
        var offset = 0

        while (offset < contentData.size) {
            val length = min(contentData.size - offset, BlockSplitRate)
            val data = contentData.sliceArray(offset until offset + length)
            val hash = hashFunc(Block(index, length, data))

            if (writeIndexes) {
                writeIntLe(index.toInt())
            }
            write(hash)
            writeIntLe(length)
            write(data)
            index++
            offset += length
        }
        if (writeIndexes) {
            writeIntLe(index.toInt())
        }
        write(hashFunc(Block(index, 0, ByteArray(0))))
        writeIntLe(0)
    }

    private fun createBlockHmacKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) = sha512(byteArrayOf(*masterSeed, *transformedKey, 0x01))

    private fun createBlockHmac(
        hmacKey: ByteArray,
        index: Long,
        length: Int,
        data: ByteArray
    ): ByteArray {
        val blockHeader = ByteArray(Long.SIZE_BYTES + Int.SIZE_BYTES)
        writeLongLittleEndian(index, blockHeader, 0)
        writeIntLittleEndian(length, blockHeader, Long.SIZE_BYTES)

        val blockKeyInput = ByteArray(Long.SIZE_BYTES + hmacKey.size)
        blockHeader.copyInto(
            destination = blockKeyInput,
            endIndex = Long.SIZE_BYTES,
        )
        hmacKey.copyInto(
            destination = blockKeyInput,
            destinationOffset = Long.SIZE_BYTES,
        )
        val blockKey = sha512(blockKeyInput)

        return createHmacSha256(blockKey).use { hmac ->
            hmac.update(blockHeader)
            hmac.update(data)
            hmac.doFinal()
        }
    }

    private fun writeLongLittleEndian(
        value: Long,
        destination: ByteArray,
        offset: Int,
    ) {
        var remaining = value
        repeat(Long.SIZE_BYTES) { index ->
            destination[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }

    private fun writeIntLittleEndian(
        value: Int,
        destination: ByteArray,
        offset: Int,
    ) {
        var remaining = value
        repeat(Int.SIZE_BYTES) { index ->
            destination[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}
