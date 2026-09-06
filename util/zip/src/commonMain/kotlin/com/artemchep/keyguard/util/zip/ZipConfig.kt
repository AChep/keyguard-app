package com.artemchep.keyguard.util.zip

/** @param encryption when non-null, every entry is encrypted with AES-256. */
data class ZipConfig(
    val encryption: Encryption? = null,
) {
    data class Encryption(
        val password: String,
    )
}
