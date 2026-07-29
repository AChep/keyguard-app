package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NativeCrypto

interface HmacState : HashState

fun createHmac(
    key: ByteArray,
    algorithm: CryptoHashAlgorithm,
): HmacState = NativeHashSessionState(
    session = NativeCrypto.primitives.createHmac(
        key = key,
        algorithm = algorithm.toNativeHashAlgorithm(),
    ),
    label = "HMAC",
)

fun createHmacSha256(
    key: ByteArray,
): HmacState = createHmac(
    key = key,
    algorithm = CryptoHashAlgorithm.SHA_256,
)
