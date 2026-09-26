package com.artemchep.keyguard.util.instance

internal const val INSTANCE_ABI_VERSION = 2

internal expect object NativeInstance {
    fun lastError(): String?

    fun acquireOrActivate(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long

    fun waitEvent(handle: Long): Long

    fun stop(handle: Long): Long

    fun close(handle: Long): Long
}
