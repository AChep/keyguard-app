package com.artemchep.keyguard.platform

actual object LeSystem {
    actual val lineSeparator: String
        get() = System.lineSeparator()
}
