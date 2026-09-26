package com.artemchep.keyguard.util.instance

// JNI exports are pinned to this object and package. Moving it requires changing the Rust ABI.
internal object NativeInstanceJni {
    external fun abiVersion(): Int

    external fun lastError(): String?

    external fun acquireOrActivate(
        coordinationDirectory: String,
        runtimeDirectory: String,
        identity: String,
        timeoutMillis: Long,
    ): Long

    external fun waitEvent(handle: Long): Long

    external fun stop(handle: Long): Long

    external fun close(handle: Long): Long
}
