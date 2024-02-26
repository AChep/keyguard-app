package com.artemchep.keyguard.common.service.zip

data class ZipConfig(
    val encryption: Encryption? = null,
) {
    data class Encryption(
        val password: String,
    )
}
