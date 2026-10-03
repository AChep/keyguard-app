package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NativeCrypto

const val DEFAULT_HASH_LENGTH = 32

/** Loads the native crypto backend and fails closed on an incompatible runtime. */
fun ensurePlatformCryptoReady() {
    NativeCrypto.ensureReady()
}

/**
 * Derives [length] bytes using HKDF with HMAC-SHA256 (RFC 5869).
 *
 * Note on [salt]: when [salt] is `null`, the HKDF *extract* step is skipped and
 * [seed] is used directly as the pseudo-random key
 * (the legacy `skipExtract` compatibility semantics).
 */
fun hkdfSha256(
    seed: ByteArray,
    salt: ByteArray? = null,
    info: ByteArray? = null,
    length: Int = DEFAULT_HASH_LENGTH,
): ByteArray = NativeCrypto.primitives.hkdfSha256(
    seed = seed,
    salt = salt,
    info = info,
    length = length,
)

fun randomBytes(length: Int): ByteArray = NativeCrypto.primitives.randomBytes(length)

fun sha256(data: ByteArray): ByteArray = NativeCrypto.primitives.sha256(data)

fun sha512(data: ByteArray): ByteArray = NativeCrypto.primitives.sha512(data)

/** Application-internal bulk AES-KDF primitive. */
fun aesEcbNoPaddingTransform(
    key: ByteArray,
    data: ByteArray,
    rounds: Int,
): ByteArray = NativeCrypto.primitives.aesEcbNoPaddingTransform(
    key = key,
    data = data,
    rounds = rounds,
)
