package com.artemchep.keyguard.util.zip

/** @param encryption when non-null, every entry is encrypted with AES-256. */
data class ZipConfig(
    val encryption: Encryption? = null,
) {
    data class Encryption(
        val password: String,
    ) {
        init {
            // An empty password disables encryption in the native
            // writer, which would silently produce a plaintext archive.
            require(password.isNotEmpty()) { "Encryption password must not be empty" }
        }
    }
}
