package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.compareUnsigned
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.readBigEndian

/**
 * Derives the short authentication string both devices show during enrollment: 6 decimal
 * digits from `HKDF-SHA256(ikm = psk, salt = handshakeHash, info = scope.sasInfo ||
 * min(staticA, staticB) || max(staticA, staticB) || scannerNonce || displayerNonce)`. The
 * statics are sorted, so both sides get the same digits; a relay that sits in the middle
 * has two different handshakes and so, almost surely, two different strings.
 *
 * The nonces stop a device in the middle from picking its handshake until the digits of
 * both sides match: the displayer commits to its nonce, see [commit], before it learns the
 * scanner's, and the scanner sends its nonce before the displayer reveals its own. So each
 * side's digits depend on a nonce the other side picked after it could no longer change
 * its own, and a device in the middle matches them with a chance of one in a million.
 *
 * Each scope has its own info, see [SyncScope.sasInfo], so two devices that pair into
 * different scopes never see the same digits.
 */
object SasDerivation {
    /** The number of digits. */
    const val DIGITS: Int = 6

    /** The size of a nonce, in bytes. */
    const val NONCE_SIZE: Int = 32

    private const val OUTPUT_BYTES = 4
    private const val MODULUS = 1_000_000L
    private const val COMMIT_DOMAIN = "keyguard-sas-commit/v1\n"

    /** Returns the commitment to the displayer's [nonce]: the SHA-256 of it, domain separated. */
    fun commit(nonce: ByteArray, crypto: P2pCrypto): ByteArray =
        crypto.sha256(COMMIT_DOMAIN.encodeToByteArray() + nonce)

    /** Returns the short authentication string, zero-padded to [DIGITS] digits. */
    @Suppress("LongParameterList") // One argument per input of the derivation.
    fun sas(
        psk: ByteArray,
        handshakeHash: ByteArray,
        staticA: ByteArray,
        staticB: ByteArray,
        scannerNonce: ByteArray,
        displayerNonce: ByteArray,
        crypto: P2pCrypto,
        scope: SyncScope,
    ): String {
        require(scannerNonce.size == NONCE_SIZE && displayerNonce.size == NONCE_SIZE) {
            "A nonce is $NONCE_SIZE bytes"
        }
        val first = if (staticA.compareUnsigned(staticB) <= 0) staticA else staticB
        val second = if (first === staticA) staticB else staticA
        val info = scope.sasInfo.encodeToByteArray() + first + second + scannerNonce + displayerNonce
        val okm = crypto.hkdfSha256(ikm = psk, salt = handshakeHash, info = info, length = OUTPUT_BYTES)
        return (okm.readBigEndian(0, okm.size) % MODULUS).toString().padStart(DIGITS, '0')
    }
}
