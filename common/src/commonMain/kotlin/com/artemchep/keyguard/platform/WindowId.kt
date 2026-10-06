package com.artemchep.keyguard.platform

import kotlin.jvm.JvmInline
import kotlinx.atomicfu.atomic

@JvmInline
value class WindowId(
    val value: Long,
) {
    companion object {
        private val nextId = atomic(0L)

        /** Creates a process-local identity for a window or activity host. */
        fun create(): WindowId = WindowId(nextId.incrementAndGet())
    }
}
