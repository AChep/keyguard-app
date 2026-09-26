package com.artemchep.keyguard.util.webauthn

private const val CBOR_BYTE_STRING_MAJOR_TYPE = 0x40
private const val CBOR_INLINE_LENGTH_LIMIT = 24
private const val CBOR_BYTE_STRING_UINT8_LENGTH: Byte = 0x58
private const val CBOR_BYTE_STRING_UINT16_LENGTH: Byte = 0x59

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
    size < CBOR_INLINE_LENGTH_LIMIT -> byteArrayOf((CBOR_BYTE_STRING_MAJOR_TYPE or size).toByte())
    size <= UByte.MAX_VALUE.toInt() -> byteArrayOf(CBOR_BYTE_STRING_UINT8_LENGTH, size.toByte())
    size <= UShort.MAX_VALUE.toInt() -> byteArrayOf(
        CBOR_BYTE_STRING_UINT16_LENGTH,
        (size ushr Byte.SIZE_BITS).toByte(),
        size.toByte(),
    )
    else -> error("authenticator data too large to CBOR-encode")
}
