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
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

internal object ContentBlocks {
    internal const val BLOCK_SPLIT_RATE: Int = 1024 * 1024
    internal const val DEFAULT_MAXIMUM_BLOCK_SIZE: Int = 16 * 1024 * 1024

    fun ver3Source(
        source: Source,
        maximumBlockSize: Int = DEFAULT_MAXIMUM_BLOCK_SIZE,
    ): Source = Ver3BlockSource(source.buffer(), maximumBlockSize)

    fun ver4Source(
        source: Source,
        masterSeed: ByteArray,
        transformedKey: ByteArray,
        maximumBlockSize: Int = DEFAULT_MAXIMUM_BLOCK_SIZE,
    ): Source =
        Ver4BlockSource(
            source = source.buffer(),
            hmacKey = createBlockHmacKey(masterSeed, transformedKey),
            maximumBlockSize = maximumBlockSize,
        )

    fun ver3Sink(sink: Sink): BlockSink = Ver3BlockSink(sink)

    fun ver4Sink(
        sink: Sink,
        masterSeed: ByteArray,
        transformedKey: ByteArray,
    ): BlockSink =
        Ver4BlockSink(
            downstream = sink,
            hmacKey = createBlockHmacKey(masterSeed, transformedKey),
        )

    fun readContentBlocksVer3x(source: BufferedSource): ByteArray {
        val content = Buffer()
        ver3Source(source).buffer().readAll(content)
        return content.readByteArray()
    }

    fun readContentBlocksVer4x(
        source: BufferedStream,
        masterSeed: ByteArray,
        transformedKey: ByteArray,
    ): ByteArray {
        val content = Buffer()
        ver4Source(source, masterSeed, transformedKey).buffer().readAll(content)
        return content.readByteArray()
    }

    internal fun writeContentBlocksVer3x(
        sink: BufferedSink,
        contentData: ByteArray,
    ) {
        val blockSink = ver3Sink(sink)
        blockSink.write(Buffer().write(contentData), contentData.size.toLong())
        blockSink.finish()
    }

    internal fun writeContentBlocksVer4x(
        sink: BufferedSink,
        contentData: ByteArray,
        masterSeed: ByteArray,
        transformedKey: ByteArray,
    ) {
        val blockSink = ver4Sink(sink, masterSeed, transformedKey)
        blockSink.write(Buffer().write(contentData), contentData.size.toLong())
        blockSink.finish()
    }

    internal abstract class BlockSink(
        private val sink: Sink,
    ) : Sink {
        private val pending = Buffer()
        protected var index = 0L
        private var finished = false
        private var closed = false

        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            checkWritable()
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid content-block write size: $byteCount"
            }
            var remaining = byteCount
            while (remaining > 0L) {
                val copied =
                    minOf(
                        remaining,
                        BLOCK_SPLIT_RATE.toLong() - pending.size,
                    )
                pending.write(source, copied)
                remaining -= copied
                if (pending.size == BLOCK_SPLIT_RATE.toLong()) emitPendingBlock()
            }
        }

        override fun flush() {
            check(!closed) { "Content-block sink is closed" }
            sink.flush()
        }

        override fun timeout(): Timeout = sink.timeout()

        fun finish() {
            checkWritable()
            finished = true
            try {
                if (pending.size > 0L) emitPendingBlock()
                writeTerminal(index)
            } finally {
                onFinished()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            pending.clear()
            onFinished()
        }

        protected abstract fun writeBlock(
            index: Long,
            data: ByteArray,
        )

        protected abstract fun writeTerminal(index: Long)

        protected open fun onFinished() = Unit

        protected fun writeToSink(block: Buffer) {
            sink.write(block, block.size)
        }

        private fun emitPendingBlock() {
            val data = pending.readByteArray()
            try {
                writeBlock(index, data)
            } finally {
                data.fill(0)
            }
            index++
        }

        private fun checkWritable() {
            check(!closed) { "Content-block sink is closed" }
            check(!finished) { "Content-block sink is already finished" }
        }
    }

    private abstract class BlockSource(
        protected val source: BufferedSource,
        protected val maximumBlockSize: Int,
    ) : Source {
        private val output = Buffer()
        protected var index = 0L
            private set
        private var finished = false
        private var closed = false

        init {
            require(maximumBlockSize > 0) { "Maximum block size must be positive" }
        }

        final override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            check(!closed) { "Content-block source is closed" }
            require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
            if (byteCount == 0L) return 0L
            if (output.size == 0L && !finished) readBlock()
            return if (output.size > 0L) {
                output.read(sink, minOf(byteCount, output.size))
            } else {
                -1L
            }
        }

        final override fun timeout(): Timeout = source.timeout()

        final override fun close() {
            if (closed) return
            closed = true
            output.clear()
            try {
                onClosed()
            } finally {
                source.close()
            }
        }

        protected abstract fun readBlock()

        protected fun emit(data: ByteArray) {
            try {
                output.write(data)
            } finally {
                data.fill(0)
            }
            index++
        }

        protected fun finish() {
            finished = true
        }

        protected open fun onClosed() = Unit
    }

    private class Ver3BlockSource(
        source: BufferedSource,
        maximumBlockSize: Int,
    ) : BlockSource(source, maximumBlockSize) {
        override fun readBlock() {
            val declaredIndex = source.readIntLe().toUInt().toLong()
            if (declaredIndex != index) {
                throw FormatError.InvalidContent(
                    "Unexpected content block index $declaredIndex; expected $index.",
                )
            }
            val expectedHash = source.readByteArray(32)
            val length = readBlockLength(source, index, maximumBlockSize)
            if (length == 0) {
                if (!expectedHash.contentEquals(ByteArray(32))) {
                    throw FormatError.InvalidContent("Terminal content block hash does not match.")
                }
                if (!source.exhausted()) {
                    throw FormatError.InvalidContent("Unexpected data after the terminal content block.")
                }
                finish()
                return
            }
            val data = source.readByteArray(length.toLong())
            if (!sha256(data).contentEquals(expectedHash)) {
                data.fill(0)
                throw FormatError.InvalidContent("Hash for block $index does not match.")
            }
            emit(data)
        }
    }

    private class Ver4BlockSource(
        source: BufferedSource,
        private val hmacKey: ByteArray,
        maximumBlockSize: Int,
    ) : BlockSource(source, maximumBlockSize) {
        override fun onClosed() {
            hmacKey.fill(0)
        }

        override fun readBlock() {
            val expectedHmac = source.readByteArray(32)
            val length = readBlockLength(source, index, maximumBlockSize)
            val data = source.readByteArray(length.toLong())
            val actualHmac = createBlockHmac(hmacKey, index, length, data)
            if (!actualHmac.constantTimeEquals(expectedHmac)) {
                data.fill(0)
                actualHmac.fill(0)
                throw FormatError.InvalidContent("HMAC for block $index does not match.")
            }
            actualHmac.fill(0)
            if (length == 0) {
                if (!source.exhausted()) {
                    throw FormatError.InvalidContent("Unexpected data after the terminal content block.")
                }
                finish()
                hmacKey.fill(0)
                return
            }
            emit(data)
        }
    }

    private class Ver3BlockSink(
        downstream: Sink,
    ) : BlockSink(downstream) {
        override fun writeBlock(
            index: Long,
            data: ByteArray,
        ) {
            require(index <= UInt.MAX_VALUE.toLong()) { "Too many KDBX content blocks" }
            writeToSink(
                Buffer()
                    .writeIntLe(index.toInt())
                    .write(sha256(data))
                    .writeIntLe(data.size)
                    .write(data),
            )
        }

        override fun writeTerminal(index: Long) {
            require(index <= UInt.MAX_VALUE.toLong()) { "Too many KDBX content blocks" }
            writeToSink(
                Buffer()
                    .writeIntLe(index.toInt())
                    .write(ByteArray(32))
                    .writeIntLe(0),
            )
        }
    }

    private class Ver4BlockSink(
        downstream: Sink,
        private val hmacKey: ByteArray,
    ) : BlockSink(downstream) {
        override fun writeBlock(
            index: Long,
            data: ByteArray,
        ) {
            writeToSink(
                Buffer()
                    .write(createBlockHmac(hmacKey, index, data.size, data))
                    .writeIntLe(data.size)
                    .write(data),
            )
        }

        override fun writeTerminal(index: Long) {
            writeToSink(
                Buffer()
                    .write(createBlockHmac(hmacKey, index, 0, ByteArray(0)))
                    .writeIntLe(0),
            )
        }

        override fun onFinished() {
            hmacKey.fill(0)
        }
    }

    private fun readBlockLength(
        source: BufferedSource,
        index: Long,
        maximumBlockSize: Int,
    ): Int {
        val length = source.readIntLe()
        if (length < 0) {
            throw FormatError.InvalidContent("Content block $index has a negative length.")
        }
        if (length > maximumBlockSize) {
            throw FormatError.InvalidContent(
                "Content block $index exceeds the $maximumBlockSize-byte limit.",
            )
        }
        return length
    }

    private fun createBlockHmacKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray,
    ) = sha512(byteArrayOf(*masterSeed, *transformedKey, 0x01))

    private fun createBlockHmac(
        hmacKey: ByteArray,
        index: Long,
        length: Int,
        data: ByteArray,
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
