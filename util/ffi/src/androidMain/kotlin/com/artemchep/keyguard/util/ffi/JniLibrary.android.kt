package com.artemchep.keyguard.util.ffi

internal actual fun loadBundledJniLibrary(name: String): Boolean {
    System.loadLibrary(name)
    return true
}
