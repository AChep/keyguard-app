package com.artemchep.jna.util

import com.sun.jna.Memory

internal class DisposableMemory(
    val memory: Memory,
    val dispose: () -> Unit,
)
