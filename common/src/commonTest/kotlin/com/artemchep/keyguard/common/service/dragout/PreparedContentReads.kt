package com.artemchep.keyguard.common.service.dragout

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** Reads the whole content through one reader lease, as a recipient would. */
internal fun PreparedContent.readAllBytes(): ByteArray = openReader().use { it.readFrom(0) }

/** Reads from [position] until the reader reports the end. */
internal fun PreparedContentReader.readFrom(position: Long): ByteArray {
    val output = Buffer()
    val chunk = ByteArray(8 * 1024)
    var offset = position
    while (true) {
        val count = readAt(offset, chunk)
        if (count == 0) return output.readByteArray()
        output.write(chunk, 0, count)
        offset += count
    }
}
