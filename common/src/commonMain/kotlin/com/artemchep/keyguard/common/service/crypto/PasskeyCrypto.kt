package com.artemchep.keyguard.common.service.crypto

enum class PasskeySignatureAlgorithm(
    val coseValue: Int,
) {
    ES256(coseValue = -7),
}

enum class PasskeyKeyProfile(
    val keyAlgorithm: String,
    val keyCurve: String,
) {
    EC_P256(
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
    ),
}

sealed interface PasskeyPublicKey {
    data class EcP256(
        val x: ByteArray,
        val y: ByteArray,
        val spki: ByteArray,
    ) : PasskeyPublicKey
}

data class PasskeyKeyMaterial(
    val profile: PasskeyKeyProfile,
    val privateKeyPkcs8: ByteArray,
    val publicKey: PasskeyPublicKey,
) {
    fun clear() {
        privateKeyPkcs8.fill(0)
        when (val key = publicKey) {
            is PasskeyPublicKey.EcP256 -> {
                key.x.fill(0)
                key.y.fill(0)
                key.spki.fill(0)
            }
        }
    }

    override fun toString(): String = "PasskeyKeyMaterial(" +
        "profile=$profile, " +
        "privateKeyPkcs8=<redacted>, " +
        "publicKey=<redacted>" +
        ")"
}

enum class PasskeyKeyError {
    MALFORMED,
    UNSUPPORTED,
    RESOURCE_LIMIT,
}

sealed interface PasskeyKeyInspectionResult {
    data class Success(
        val keyMaterial: PasskeyKeyMaterial,
    ) : PasskeyKeyInspectionResult

    data class Error(
        val reason: PasskeyKeyError,
    ) : PasskeyKeyInspectionResult
}

sealed interface PasskeySignResult {
    data class Success(
        val signatureDer: ByteArray,
    ) : PasskeySignResult

    data class Error(
        val reason: PasskeyKeyError,
    ) : PasskeySignResult
}

interface PasskeyCrypto {
    val supportedAlgorithms: Set<PasskeySignatureAlgorithm>

    /**
     * Returns caller-owned key material. The caller must invoke
     * [PasskeyKeyMaterial.clear] after copying the representations it needs.
     */
    fun generate(
        algorithm: PasskeySignatureAlgorithm,
    ): PasskeyKeyMaterial

    /**
     * Validates and canonicalizes PKCS#8 key material. A successful result is
     * caller-owned and must be cleared after use.
     */
    fun inspect(
        privateKeyPkcs8: ByteArray,
    ): PasskeyKeyInspectionResult

    fun sign(
        algorithm: PasskeySignatureAlgorithm,
        privateKeyPkcs8: ByteArray,
        data: ByteArray,
    ): PasskeySignResult
}
