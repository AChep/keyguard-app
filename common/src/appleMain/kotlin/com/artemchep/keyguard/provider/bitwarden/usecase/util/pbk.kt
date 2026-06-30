package com.artemchep.keyguard.provider.bitwarden.usecase.util

actual fun pbk(privateKey: ByteArray): ByteArray {
    throw UnsupportedOperationException("RSA public key derivation is not supported on iOS yet.")
}
