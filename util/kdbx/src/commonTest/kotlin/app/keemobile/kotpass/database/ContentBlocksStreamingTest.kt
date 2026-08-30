package app.keemobile.kotpass.database

import app.keemobile.kotpass.errors.FormatError
import okio.Buffer
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ContentBlocksStreamingTest {
    @Test
    fun v3BlocksRoundTripAcrossBoundaries() {
        blockSizes().forEach { size ->
            val content = testContent(size)
            val encoded = Buffer()
            val sink = ContentBlocks.ver3Sink(encoded)
            writeChunked(sink, content)
            sink.finish()

            val decoded = readChunked(ContentBlocks.ver3Source(encoded))
            assertContentEquals(content, decoded, "V3 content size $size")
        }
    }

    @Test
    fun v4BlocksRoundTripAcrossBoundaries() {
        val masterSeed = ByteArray(32) { it.toByte() }
        val transformedKey = ByteArray(32) { (it * 3).toByte() }
        blockSizes().forEach { size ->
            val content = testContent(size)
            val encoded = Buffer()
            val sink = ContentBlocks.ver4Sink(encoded, masterSeed, transformedKey)
            writeChunked(sink, content)
            sink.finish()

            val decoded =
                readChunked(
                    ContentBlocks.ver4Source(encoded, masterSeed, transformedKey),
                )
            assertContentEquals(content, decoded, "V4 content size $size")
        }
    }

    @Test
    fun v3IgnoresDataAfterValidatedTerminalBlock() {
        val content = testContent(257)
        val encoded = Buffer()
        val sink = ContentBlocks.ver3Sink(encoded)
        writeChunked(sink, content)
        sink.finish()
        encoded.write(testContent(129))

        val decoded = readChunked(ContentBlocks.ver3Source(encoded))

        assertContentEquals(content, decoded)
    }

    @Test
    fun v4IgnoresDataAfterValidatedTerminalBlock() {
        val masterSeed = ByteArray(32) { it.toByte() }
        val transformedKey = ByteArray(32) { (it * 3).toByte() }
        val content = testContent(257)
        val encoded = Buffer()
        val sink = ContentBlocks.ver4Sink(encoded, masterSeed, transformedKey)
        writeChunked(sink, content)
        sink.finish()
        encoded.write(testContent(129))

        val decoded =
            readChunked(
                ContentBlocks.ver4Source(encoded, masterSeed, transformedKey),
            )

        assertContentEquals(content, decoded)
    }

    @Test
    fun rejectsNegativeAndOversizedBlockLengthsBeforeAllocation() {
        val negativeV3 =
            Buffer()
                .writeIntLe(0)
                .write(ByteArray(32))
                .writeIntLe(-1)
        assertFailsWith<FormatError.InvalidContent> {
            ContentBlocks.ver3Source(negativeV3).read(Buffer(), 1)
        }

        val oversizedV4 =
            Buffer()
                .write(ByteArray(32))
                .writeIntLe(33)
        assertFailsWith<FormatError.InvalidContent> {
            ContentBlocks
                .ver4Source(
                    source = oversizedV4,
                    masterSeed = ByteArray(32),
                    transformedKey = ByteArray(32),
                    maximumBlockSize = 32,
                ).read(Buffer(), 1)
        }
    }

    private fun blockSizes() =
        intArrayOf(
            0,
            1,
            ContentBlocks.BLOCK_SPLIT_RATE - 1,
            ContentBlocks.BLOCK_SPLIT_RATE,
            ContentBlocks.BLOCK_SPLIT_RATE + 1,
        )

    private fun testContent(size: Int): ByteArray = ByteArray(size) { index -> (index * 31).toByte() }

    private fun writeChunked(
        sink: ContentBlocks.BlockSink,
        content: ByteArray,
    ) {
        val source = Buffer().write(content)
        val chunks = intArrayOf(1, 15, 16, 17, 4095, 64 * 1024 + 1)
        var index = 0
        while (source.size > 0L) {
            val length = minOf(source.size, chunks[index % chunks.size].toLong())
            sink.write(source, length)
            index++
        }
    }

    private fun readChunked(source: Source): ByteArray {
        val output = Buffer()
        val chunks = longArrayOf(1, 17, 4097, 64 * 1024L - 1)
        var index = 0
        while (true) {
            val read = source.read(output, chunks[index % chunks.size])
            if (read == -1L) break
            index++
        }
        return output.readByteArray()
    }
}
