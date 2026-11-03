package com.artemchep.keyguard.util.io.spool

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal typealias PositionalRead = (Long, ByteArray, Int, Int) -> Int

/** The expected bytes come only from the original payload, never from another reader. */
internal fun assertPositionalRead(
    payload: ByteArray,
    read: PositionalRead,
    position: Long,
    outputSize: Int = 19,
    startIndex: Int = 3,
    endIndex: Int = outputSize - 2,
) {
    val output = ByteArray(outputSize) { 0x5a }
    val expected = output.copyOf()
    val count = minOf((endIndex - startIndex).toLong(), (payload.size - position).coerceAtLeast(0)).toInt()
    repeat(count) { index -> expected[startIndex + index] = payload[position.toInt() + index] }
    val context = "size=${payload.size}, position=$position, destination=[$startIndex, $endIndex)"
    assertEquals(count, read(position, output, startIndex, endIndex), context)
    assertContentEquals(expected, output, context)
}

internal fun assertPositionalReadContract(payload: ByteArray, read: PositionalRead) {
    val positions = listOf(
        0L, 1L, payload.size / 2L, (payload.size - 1L).coerceAtLeast(0),
        payload.size.toLong(), payload.size + 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
    )
    for (position in positions) {
        assertPositionalRead(payload, read, position)
        assertPositionalRead(payload, read, position, outputSize = 0, startIndex = 0, endIndex = 0)
        for (index in listOf(0, 3, 19)) {
            assertPositionalRead(payload, read, position, startIndex = index, endIndex = index)
        }
    }
    // These requests cross several transfer boundaries, with a nonzero destination offset.
    assertPositionalRead(payload, read, 0, outputSize = payload.size + 13)
    assertPositionalRead(payload, read, 7, outputSize = payload.size + 13)
    val random = Random(527)
    repeat(48) {
        val outputSize = random.nextInt(1, 4097)
        val startIndex = random.nextInt(outputSize + 1)
        assertPositionalRead(
            payload, read, random.nextLong(payload.size + 2L), outputSize,
            startIndex, random.nextInt(startIndex, outputSize + 1),
        )
    }
    // An EOF result must not prevent a subsequent earlier read.
    assertPositionalRead(payload, read, 0)
}

internal fun assertInvalidPositionalReads(read: PositionalRead) {
    val output = ByteArray(4) { 0x5a }
    val invalidRanges = listOf(
        -1 to 0, 0 to -1, 3 to 2, 0 to 5, 5 to 5,
        Int.MIN_VALUE to 0, 0 to Int.MAX_VALUE,
    )
    for (position in listOf(0L, Long.MAX_VALUE)) {
        for ((start, end) in invalidRanges) {
            assertFailsWith<IllegalArgumentException>("position=$position, range=[$start, $end)") {
                read(position, output, start, end)
            }
        }
    }
    for (position in listOf(-1L, Long.MIN_VALUE)) {
        assertFailsWith<IllegalArgumentException> { read(position, output, 0, output.size) }
        assertFailsWith<IllegalArgumentException> { read(position, output, 2, 2) }
        assertFailsWith<IllegalArgumentException> { read(position, ByteArray(0), 0, 0) }
    }
    assertContentEquals(ByteArray(4) { 0x5a }, output)
}

internal fun memorySnapshot(
    payload: ByteArray,
    writeSizes: List<Int> = listOf(payload.size.coerceAtLeast(1)),
    flushEachWrite: Boolean = false,
): RandomAccessByteSnapshot = AdaptiveSpool(
    memoryLimitBytes = payload.size.toLong(),
    maximumBytes = payload.size.toLong(),
    spillFactory = { error("The payload should fit in memory") },
).use { spool ->
    spool.sink().use { sink ->
        var offset = 0
        var writeIndex = 0
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + writeSizes[writeIndex % writeSizes.size])
            sink.write(payload, offset, end)
            if (flushEachWrite) sink.flush()
            offset = end
            writeIndex += 1
        }
    }
    assertIs<RandomAccessByteSnapshot>(spool.seal())
}
