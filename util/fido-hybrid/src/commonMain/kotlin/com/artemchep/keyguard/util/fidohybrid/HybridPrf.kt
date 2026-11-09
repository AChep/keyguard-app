@file:Suppress("MagicNumber") // CTAP COSE labels and PIN/UV protocol sizes.

package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.nativecrypto.NativeCryptoCtapPrf
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfValues

/** A fresh key agreement for this QR transaction; no PIN or reusable UV tokens. */
internal class HybridPrfSession {
    private var keyPair: ByteArray? = null

    fun keyAgreement(parameters: Map<*, *>): ByteArray {
        val protocol = parameters[1L]
        // Only getKeyAgreement: there is no PIN, and verification happens on the phone.
        val status =
            when {
                parameters[2L] == null -> HybridCtap.MISSING_PARAMETER
                parameters[2L] != 2L -> HybridCtap.INVALID_SUBCOMMAND
                protocol == null -> HybridCtap.MISSING_PARAMETER
                protocol !in listOf(1L, 2L) -> HybridCtap.INVALID_PARAMETER
                else -> null
            }
        if (status != null) throw HybridCtapException(status)
        val pair = keyPair ?: NativeCryptoCtapPrf.generate().also { keyPair = it }
        val key =
            mapOf(
                1 to 2,
                3 to -25,
                -1 to 1,
                -2 to pair.copyOfRange(33, 65),
                -3 to pair.copyOfRange(65, 97),
            )
        return HybridCtap.success(HybridCbor.encode(mapOf(1 to key)))
    }

    fun open(input: Any?): HybridPrfContext? {
        if (input == null) return null
        val parameters = input as? Map<*, *> ?: throw HybridCtapException(HybridCtap.INVALID_PARAMETER)
        val protocol = (parameters[4L] ?: 1L) as? Long
        if (protocol !in listOf(1L, 2L)) throw HybridCtapException(HybridCtap.INVALID_PARAMETER)
        return open(parameters, requireNotNull(protocol).toInt())
    }

    private fun open(
        parameters: Map<*, *>,
        protocol: Int,
    ): HybridPrfContext {
        val peer = peerKey(parameters[1L])
        val encrypted = parameters[2L] as? ByteArray ?: throw HybridCtapException(HybridCtap.MISSING_PARAMETER)
        val authentication = parameters[3L] as? ByteArray ?: throw HybridCtapException(HybridCtap.MISSING_PARAMETER)
        validateSalts(protocol, encrypted, authentication)
        val privateKey = privateKey()
        val decoded =
            try {
                decrypt(protocol, privateKey, peer, encrypted, authentication)
            } finally {
                privateKey.fill(0)
            }
        return try {
            HybridPrfContext(
                protocol,
                decoded.copyOf(64),
                WebAuthnPrfValues(
                    decoded.copyOfRange(64, 96),
                    decoded.takeIf { it.size == 128 }?.copyOfRange(96, 128),
                ),
            )
        } finally {
            decoded.fill(0)
        }
    }

    private fun privateKey(): ByteArray = (keyPair ?: throw HybridCtapException(HybridCtap.PIN_AUTH_INVALID)).copyOf(32)

    private fun peerKey(input: Any?): ByteArray {
        val peer = input as? Map<*, *> ?: throw HybridCtapException(HybridCtap.MISSING_PARAMETER)
        val isP256 = peer[1L] == 2L && peer[3L] == -25L && peer[-1L] == 1L
        val x = peer[-2L] as? ByteArray
        val y = peer[-3L] as? ByteArray
        if (!isP256 || x?.size != 32 || y?.size != 32) throw HybridCtapException(HybridCtap.INVALID_PARAMETER)
        return byteArrayOf(4) + x + y
    }

    private fun validateSalts(
        protocol: Int,
        encrypted: ByteArray,
        authentication: ByteArray,
    ) {
        val lengths = if (protocol == 1) listOf(32, 64) else listOf(48, 80)
        if (encrypted.size !in lengths) throw HybridCtapException(HybridCtap.INVALID_PARAMETER)
        if (authentication.size != if (protocol == 1) 16 else 32) {
            throw HybridCtapException(HybridCtap.PIN_AUTH_INVALID)
        }
    }

    private fun decrypt(
        protocol: Int,
        privateKey: ByteArray,
        peer: ByteArray,
        encrypted: ByteArray,
        authentication: ByteArray,
    ): ByteArray =
        try {
            NativeCryptoCtapPrf.decrypt(protocol, privateKey, peer, encrypted, authentication)
        } catch (e: NativeCryptoException) {
            val status =
                if (e.code == NativeCryptoErrorCode.AUTHENTICATION_FAILED) {
                    HybridCtap.PIN_AUTH_INVALID
                } else {
                    HybridCtap.INVALID_PARAMETER
                }
            throw HybridCtapException(status)
        }

    fun clear() {
        keyPair?.fill(0)
        keyPair = null
    }
}

/** Authenticated salts and their response key; owned by a single CTAP command. */
class HybridPrfContext internal constructor(
    private val protocol: Int,
    private val sharedSecret: ByteArray,
    val values: WebAuthnPrfValues,
) {
    fun encrypt(results: WebAuthnPrfValues): ByteArray {
        val plaintext = results.first + (results.second ?: byteArrayOf())
        return try {
            NativeCryptoCtapPrf.encrypt(protocol, sharedSecret, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun clear() {
        sharedSecret.fill(0)
        values.clear()
    }
}
