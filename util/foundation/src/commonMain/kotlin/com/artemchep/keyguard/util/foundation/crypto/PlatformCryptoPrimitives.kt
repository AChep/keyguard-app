package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm

/** Loads the native crypto backend and fails closed on an incompatible runtime. */
fun ensurePlatformCryptoReady() {
    NativeCrypto.ensureReady()
}

class PlatformCryptoPrimitives : CryptoPrimitives {
    private val delegate = NativeCrypto.primitives

    override fun hkdfSha256(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = delegate.hkdfSha256(
        seed = seed,
        salt = salt,
        info = info,
        length = length,
    )

    override fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = delegate.pbkdf2Sha256(
        seed = seed,
        salt = salt,
        iterations = iterations,
        length = length,
    )

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
        length: Int,
    ): ByteArray = delegate.argon2(
        mode = mode.toNative(),
        seed = seed,
        salt = salt,
        iterations = iterations,
        memoryKb = memoryKb,
        parallelism = parallelism,
        length = length,
    )

    override fun randomBytes(length: Int): ByteArray = delegate.randomBytes(length)

    override fun randomInt(): Int = delegate.randomInt()

    override fun randomInt(until: Int): Int = delegate.randomInt(until)

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = delegate.hmac(
        key = key,
        data = data,
        algorithm = algorithm.toNative(),
    )

    override fun sha1(data: ByteArray): ByteArray = delegate.sha1(data)

    override fun sha256(data: ByteArray): ByteArray = delegate.sha256(data)

    override fun sha512(data: ByteArray): ByteArray = delegate.sha512(data)

    override fun md5(data: ByteArray): ByteArray = delegate.md5(data)

    override fun aesEcbNoPaddingEncrypt(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = delegate.aesEcbNoPaddingEncrypt(
        key = key,
        data = data,
    )

    override fun aesCbcPkcs7Encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = delegate.aesCbcPkcs7Encrypt(
        key = key,
        iv = iv,
        data = data,
    )

    override fun aesCbcPkcs7Decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray = delegate.aesCbcPkcs7Decrypt(
        key = key,
        iv = iv,
        data = data,
    )

    private fun Argon2Mode.toNative(): NativeArgon2Mode = when (this) {
        Argon2Mode.ARGON2_D -> NativeArgon2Mode.ARGON2_D
        Argon2Mode.ARGON2_I -> NativeArgon2Mode.ARGON2_I
        Argon2Mode.ARGON2_ID -> NativeArgon2Mode.ARGON2_ID
    }

    private fun CryptoHashAlgorithm.toNative(): NativeHashAlgorithm = when (this) {
        CryptoHashAlgorithm.SHA_1 -> NativeHashAlgorithm.SHA_1
        CryptoHashAlgorithm.SHA_256 -> NativeHashAlgorithm.SHA_256
        CryptoHashAlgorithm.SHA_512 -> NativeHashAlgorithm.SHA_512
        CryptoHashAlgorithm.MD5 -> NativeHashAlgorithm.MD5
    }
}
