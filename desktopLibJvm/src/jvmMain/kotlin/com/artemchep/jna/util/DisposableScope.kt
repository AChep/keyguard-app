package com.artemchep.jna.util

import com.sun.jna.Memory

internal class DisposableScope {
    private val items = mutableListOf<() -> Unit>()

    fun register(
        disposableMemory: DisposableMemory,
    ): Memory {
        items.add(disposableMemory.dispose)
        return disposableMemory.memory
    }

    fun dispose() {
        items.forEach { dispose -> dispose() }
    }
}
