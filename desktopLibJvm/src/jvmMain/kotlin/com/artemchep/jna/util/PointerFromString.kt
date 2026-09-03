package com.artemchep.jna.util

import com.sun.jna.Memory

internal fun String.asMemory() = encodeToByteArray()
    // Pad with a single NUL terminator.
    .let { bytes -> bytes.copyOf(bytes.size + 1) }
    .asMemory()

internal fun ByteArray.asMemory() = kotlin.run {
    val memory = Memory(size.toLong()).apply {
        write(0L, this@asMemory, 0, size)
    }
    DisposableMemory(
        memory = memory,
        dispose = {
            memory.clear()
        },
    )
}
