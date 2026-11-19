package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.p2psync.compareUnsigned
import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.util.foundation.constantTimeEquals

/**
 * The 6-digit code that the app and the extension both show while pairing,
 * README section 8: the derivation of the P2P `SasDerivation` with an empty
 * PSK and the browser's own domain strings.
 *
 * The extension commits to its nonce before it learns the app's, and the app
 * sends its nonce before it learns the extension's; a party in the middle
 * runs two handshakes and matches both codes with a chance of 1 in 10^6.
 */
internal object BrowserPairingCode {
    /** The number of digits. */
    const val DIGITS: Int = 6

    private const val OUTPUT_BYTES = 4
    private const val MODULUS = 1_000_000L
    private const val COMMIT_DOMAIN = "keyguard-browser-commit/v1\n"
    private const val SAS_INFO = "keyguard-browser-sas/v1"

    /** The commitment to the extension's [nonce]: its SHA-256, domain separated. */
    fun commit(nonce: ByteArray, crypto: CryptoGenerator): ByteArray =
        crypto.hashSha256(COMMIT_DOMAIN.encodeToByteArray() + nonce)

    /** Whether [commit] commits to [nonce]; compares in constant time. */
    fun matches(commit: ByteArray, nonce: ByteArray, crypto: CryptoGenerator): Boolean =
        commit.constantTimeEquals(commit(nonce, crypto))

    /**
     * The code, zero-padded to [DIGITS] digits. The static keys are sorted as
     * unsigned bytes, so both sides get the same digits; the committing side's
     * nonce comes last.
     */
    @Suppress("LongParameterList") // One argument per input of the derivation.
    fun code(
        handshakeHash: ByteArray,
        appStatic: ByteArray,
        extensionStatic: ByteArray,
        appNonce: ByteArray,
        extensionNonce: ByteArray,
        crypto: CryptoGenerator,
    ): String {
        require(appNonce.size == BrowserProtocol.NONCE_SIZE && extensionNonce.size == BrowserProtocol.NONCE_SIZE) {
            "A nonce is ${BrowserProtocol.NONCE_SIZE} bytes"
        }
        val appFirst = appStatic.compareUnsigned(extensionStatic) <= 0
        val first = if (appFirst) appStatic else extensionStatic
        val second = if (appFirst) extensionStatic else appStatic
        val info = SAS_INFO.encodeToByteArray() + first + second + appNonce + extensionNonce
        val okm = crypto.hkdf(seed = ByteArray(0), salt = handshakeHash, info = info, length = OUTPUT_BYTES)
        return (okm.readBigEndian(0, okm.size) % MODULUS).toString().padStart(DIGITS, '0')
    }
}
