package com.artemchep.keyguard.util.ffi

import kotlin.concurrent.Volatile

/**
 * A C library linked into the binary, checked once per process before its first call: the
 * Apple counterpart of `JniLibrary`. [verify] runs until it returns without throwing, such
 * as when the library's ABI version matches; what it throws passes through.
 */
class StaticLibrary(
    private val verify: () -> Unit,
) {
    @Volatile
    private var verified = false

    fun ensureVerified() {
        if (verified) return
        verify()
        verified = true
    }
}
