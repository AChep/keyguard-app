package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.util.hexToByteArray

/**
 * The COSE_Key (RFC 9052 §7) for an ES256 / P-256 public key, from its 32-byte affine
 * coordinates [x] and [y]: a fixed CBOR map prefix, then X, a marker, then Y. This is the
 * exact byte layout the credential's `credentialPublicKey` must use inside attested
 * credential data.
 */
fun coseKeyEs256(
    x: ByteArray,
    y: ByteArray,
): ByteArray = "A5010203262001215820".hexToByteArray() + x + "225820".hexToByteArray() + y

/**
 * The WebAuthn attestation object for "none" attestation:
 * CBOR `{ "fmt": "none", "attStmt": {}, "authData": <authData> }`
 * (WebAuthn L3 §8.7). Hand-encoded so there is no platform CBOR dependency.
 */
fun webAuthnNoneAttestationObject(
    authData: ByteArray,
): ByteArray = "a363666d74646e6f6e656761747453746d74a0686175746844617461".hexToByteArray() +
        cborByteStringHeader(authData.size) +
        authData

private fun cborByteStringHeader(size: Int): ByteArray = when {
    size < 24 -> byteArrayOf((0x40 or size).toByte())
    size < 256 -> byteArrayOf(0x58.toByte(), size.toByte())
    size < 65536 -> byteArrayOf(0x59.toByte(), (size ushr 8).toByte(), size.toByte())
    else -> error("authenticator data too large to CBOR-encode")
}
