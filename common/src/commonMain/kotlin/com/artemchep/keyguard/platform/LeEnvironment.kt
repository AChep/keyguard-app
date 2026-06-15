package com.artemchep.keyguard.platform

expect object LeEnvironment {
    fun getenv(name: String): String?
}
