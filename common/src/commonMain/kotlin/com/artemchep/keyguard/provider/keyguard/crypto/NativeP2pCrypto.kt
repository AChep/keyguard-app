package com.artemchep.keyguard.provider.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

/**
 * The cryptographic primitives of device-to-device sync, backed by the
 * native crypto library.
 */
class NativeP2pCrypto(
    private val cryptoGenerator: CryptoGenerator,
) : P2pCrypto {
    override fun sha256(bytes: ByteArray): ByteArray = cryptoGenerator.hashSha256(bytes)

    override fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = cryptoGenerator.hkdf(
        seed = ikm,
        salt = salt,
        info = info,
        length = length,
    )

    override fun ed25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray = NativeCryptoPrimitives.ed25519Sign(
        privateKey = privateKey,
        data = data,
    )

    override fun ed25519Verify(
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = publicKey.size == ED25519_PUBLIC_KEY_SIZE &&
        NativeCryptoPrimitives.ed25519Verify(
            publicKey = publicKey,
            data = data,
            signature = signature,
        )

    override fun randomBytes(count: Int): ByteArray = cryptoGenerator.seed(length = count)

    private companion object {
        /** The native verifier requires a 32-byte key; anything else never verifies. */
        const val ED25519_PUBLIC_KEY_SIZE = 32
    }
}
