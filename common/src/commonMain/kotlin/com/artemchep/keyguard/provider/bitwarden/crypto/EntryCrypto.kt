package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.text.Base64Service

class EntryCrypto(
    val base64Service: Base64Service,
    /**
     * A function that is either encrypts the plain text or
     * decrypts it, depending on the mode.
     */
    val transform: (String) -> ByteArray,
    val mode: Mode,
) {
    enum class Mode {
        ENCRYPT,
        DECRYPT,
    }
}

fun EntryCrypto.transformToString(value: String): String = String(transform(value))

fun EntryCrypto.transformToBase64(value: String): String = whatIf(
    isEncrypt = { base64Service.decodeToString(value).let(::transformToString) },
    isDecrypt = { transform(value).let(base64Service::encodeToString) },
)

inline fun <T> EntryCrypto.whatIf(
    isEncrypt: EntryCrypto.() -> T,
    isDecrypt: EntryCrypto.() -> T,
): T = when (mode) {
    EntryCrypto.Mode.DECRYPT -> isDecrypt()
    EntryCrypto.Mode.ENCRYPT -> isEncrypt()
}
