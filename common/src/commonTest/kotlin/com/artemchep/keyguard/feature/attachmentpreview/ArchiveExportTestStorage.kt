@file:OptIn(com.artemchep.keyguard.util.io.InternalKeyguardIoApi::class)

package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.util.io.scratch.PrivateTemporaryStorage
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource

internal class ArchiveExportTestStorage(
    private val bytes: ByteArray = ByteArray(32_768) { it.toByte() },
) : PrivateTemporaryStorage {
    var onRead: (Int) -> Unit = {}
    var closed = false
        private set

    override fun source(): RawSource {
        check(!closed)
        return object : RawSource {
            private var position = 0
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                check(!closed)
                if (byteCount == 0L || position == bytes.size) return if (byteCount == 0L) 0 else -1
                val count = minOf(byteCount, (bytes.size - position).toLong()).toInt()
                sink.write(bytes, position, position + count)
                position += count
                onRead(position)
                return count.toLong()
            }
            override fun close() = Unit
        }
    }

    override fun sink(): RawSink = error("The test export is already sealed")
    override fun sealForReading() = Unit
    override fun close() { closed = true }
}
