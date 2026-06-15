package com.artemchep.keyguard.platform

actual object LeEnvironment {
    actual fun getenv(name: String): String? = System.getenv(name)
}
