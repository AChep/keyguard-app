package com.artemchep.jna.util

import com.sun.jna.Memory

internal fun String.asMemory() = kotlin.run {
    val bytes = encodeToByteArray()
    val memory = Memory(bytes.size + 1L).apply {
        write(0L, bytes, 0, bytes.size)
        setByte(bytes.size.toLong(), 0)
    }
    DisposableMemory(
        memory = memory,
        dispose = {
            memory.clear()
        },
    )
}
