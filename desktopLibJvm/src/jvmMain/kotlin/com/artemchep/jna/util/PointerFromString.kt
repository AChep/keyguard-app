package com.artemchep.jna.util

import com.sun.jna.Memory

internal fun String.asMemory() = kotlin.run {
    val memory = Memory(length + 1L).apply {
        val str = this@asMemory
        setString(0, str)
    }
    DisposableMemory(
        memory = memory,
        dispose = {
            memory.clear()
        },
    )
}
