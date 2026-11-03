package com.artemchep.keyguard.test

import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import kotlinx.io.IOException

/**
 * An in-memory [RandomAccessByteSnapshot] whose readers copy straight from [bytes].
 *
 * It counts reader opens, reads and closes, and rejects use after its own close.
 * It fails on request: the n-th reader open or read, every reader close, or its
 * own close. Failure messages pose as private paths, so tests can check redaction.
 */
internal class TestByteSnapshot(
    private val bytes: ByteArray,
    override val size: Long = bytes.size.toLong(),
    private val failOpenAt: Int = -1,
    private val failReadAt: Int = -1,
    private val failReaderClose: Boolean = false,
    private val failClose: Boolean = false,
) : RandomAccessByteSnapshot {
    var readerOpens = 0
        private set
    var readerCloses = 0
        private set
    var reads = 0
        private set
    var closes = 0
        private set

    override fun openReader(): ByteSnapshotReader {
        check(closes == 0) { "Snapshot is closed" }
        readerOpens += 1
        if (readerOpens == failOpenAt) throw IOException("Synthetic open failure")
        return object : ByteSnapshotReader {
            override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
                check(closes == 0) { "Snapshot is closed" }
                reads += 1
                if (reads == failReadAt) throw IOException("Synthetic read failure")
                if (position >= bytes.size) return 0
                val count = minOf(endIndex - startIndex, bytes.size - position.toInt())
                bytes.copyInto(data, startIndex, position.toInt(), position.toInt() + count)
                return count
            }

            override fun close() {
                readerCloses += 1
                if (failReaderClose) throw IOException("/private/source/name/must/not/be/logged")
            }
        }
    }

    override fun close() {
        closes += 1
        check(!failClose) { "/private/path/must/not/be/logged" }
    }
}
