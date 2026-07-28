package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

fun pbk(privateKey: ByteArray): ByteArray =
    NativeCryptoPrimitives.rsaPublicKeySpkiFromPkcs8(privateKey)
