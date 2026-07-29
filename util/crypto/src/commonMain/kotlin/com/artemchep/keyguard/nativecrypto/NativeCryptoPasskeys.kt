@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public enum class NativePasskeyAlgorithm {
    ES256,
}

public enum class NativePasskeyKeyProfile {
    EC_P256,
}

public data class NativePasskeyKeyMaterial(
    val profile: NativePasskeyKeyProfile,
    val privateKeyPkcs8: ByteArray,
    val publicKeyX: ByteArray,
    val publicKeyY: ByteArray,
    val publicKeySpki: ByteArray,
) {
    public fun clear() {
        privateKeyPkcs8.fill(0)
        publicKeyX.fill(0)
        publicKeyY.fill(0)
        publicKeySpki.fill(0)
    }

    override fun toString(): String = "NativePasskeyKeyMaterial(" +
        "profile=$profile, " +
        "privateKeyPkcs8=<redacted>, " +
        "publicKeyX=<redacted>, " +
        "publicKeyY=<redacted>, " +
        "publicKeySpki=<redacted>" +
        ")"
}

public enum class NativePasskeyKeyError {
    MALFORMED,
    UNSUPPORTED,
    RESOURCE_LIMIT,
}

public sealed interface NativePasskeyKeyInspectionResult {
    public data class Success(
        val keyMaterial: NativePasskeyKeyMaterial,
    ) : NativePasskeyKeyInspectionResult

    public data class Error(
        val reason: NativePasskeyKeyError,
    ) : NativePasskeyKeyInspectionResult
}

public sealed interface NativePasskeySignResult {
    public data class Success(
        val signatureDer: ByteArray,
    ) : NativePasskeySignResult

    public data class Error(
        val reason: NativePasskeyKeyError,
    ) : NativePasskeySignResult
}

public object NativeCryptoPasskeys {
    private const val MAX_PRIVATE_KEY_BYTES: Int = 4 * 1024
    private const val MAX_SIGN_DATA_BYTES: Int = 64 * 1024
    private const val P256_COORDINATE_BYTES: Int = 32
    private const val MIN_ES256_DER_SIGNATURE_BYTES: Int = 8
    private const val MAX_ES256_DER_SIGNATURE_BYTES: Int = 80

    public fun generate(
        algorithm: NativePasskeyAlgorithm,
    ): NativePasskeyKeyMaterial {
        val operation = "passkey_key_generate"
        val payload = NativeCrypto.call(
            operationName = operation,
            operation = PasskeyKeyGenerateOperationProto(
                PasskeyKeyGenerateRequestProto(algorithm.toProto()),
            ),
        ).requireBytes(operation)
        return decodePayload<PasskeyKeyMaterialProto>(operation, payload)
            .let { value -> decodeMaterial(operation, value) }
    }

    public fun inspect(
        privateKeyPkcs8: ByteArray,
    ): NativePasskeyKeyInspectionResult {
        if (privateKeyPkcs8.size > MAX_PRIVATE_KEY_BYTES) {
            return NativePasskeyKeyInspectionResult.Error(
                NativePasskeyKeyError.RESOURCE_LIMIT,
            )
        }
        val operation = "passkey_key_inspect"
        val payload = NativeCrypto.call(
            operationName = operation,
            operation = PasskeyKeyInspectOperationProto(
                PasskeyKeyInspectRequestProto(privateKeyPkcs8),
            ),
        ).requireBytes(operation)
        val result = decodePayload<PasskeyKeyInspectionProto>(operation, payload)
        return decodeInspectionResult(operation, result)
    }

    public fun sign(
        algorithm: NativePasskeyAlgorithm,
        privateKeyPkcs8: ByteArray,
        data: ByteArray,
    ): NativePasskeySignResult {
        if (privateKeyPkcs8.size > MAX_PRIVATE_KEY_BYTES) {
            return NativePasskeySignResult.Error(NativePasskeyKeyError.RESOURCE_LIMIT)
        }
        if (data.size > MAX_SIGN_DATA_BYTES) {
            throw NativeCryptoException(
                operation = "passkey_sign",
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        val operation = "passkey_sign"
        val payload = NativeCrypto.call(
            operationName = operation,
            operation = PasskeySignOperationProto(
                PasskeySignRequestProto(
                    algorithm = algorithm.toProto(),
                    privateKeyPkcs8 = privateKeyPkcs8,
                    data = data,
                ),
            ),
        ).requireBytes(operation)
        val result = decodePayload<PasskeySignResultProto>(operation, payload)
        return decodeSignResult(operation, algorithm, result)
    }

    internal fun decodeInspectionResult(
        operation: String,
        result: PasskeyKeyInspectionProto,
    ): NativePasskeyKeyInspectionResult {
        val keyMaterial = result.keyMaterial
        var ownershipTransferred = false
        return try {
            when {
                keyMaterial != null &&
                        result.error == PasskeyKeyErrorProto.UNSPECIFIED -> {
                    val decoded = decodeMaterial(operation, keyMaterial)
                    val success = NativePasskeyKeyInspectionResult.Success(decoded)
                    ownershipTransferred = true
                    success
                }

                keyMaterial == null &&
                        result.error != PasskeyKeyErrorProto.UNSPECIFIED ->
                    NativePasskeyKeyInspectionResult.Error(result.error.toPublic(operation))

                else -> malformed(operation)
            }
        } finally {
            if (!ownershipTransferred) {
                keyMaterial?.scrub()
            }
        }
    }

    internal fun decodeSignResult(
        operation: String,
        algorithm: NativePasskeyAlgorithm,
        result: PasskeySignResultProto,
    ): NativePasskeySignResult {
        val signature = result.signature
        var ownershipTransferred = false
        return try {
            when {
                signature != null &&
                        result.error == PasskeyKeyErrorProto.UNSPECIFIED -> {
                    if (
                        signature.algorithm != algorithm.toProto() ||
                        signature.signatureDer.size !in
                        MIN_ES256_DER_SIGNATURE_BYTES..MAX_ES256_DER_SIGNATURE_BYTES ||
                        signature.signatureDer.firstOrNull() != 0x30.toByte()
                    ) {
                        malformed(operation)
                    }
                    val success = NativePasskeySignResult.Success(signature.signatureDer)
                    ownershipTransferred = true
                    success
                }

                signature == null &&
                        result.error != PasskeyKeyErrorProto.UNSPECIFIED ->
                    NativePasskeySignResult.Error(result.error.toPublic(operation))

                else -> malformed(operation)
            }
        } finally {
            if (!ownershipTransferred) {
                signature?.signatureDer?.fill(0)
            }
        }
    }

    private fun decodeMaterial(
        operation: String,
        value: PasskeyKeyMaterialProto,
    ): NativePasskeyKeyMaterial {
        var ownershipTransferred = false
        return try {
            val profile = when (value.profile) {
                PasskeyKeyProfileProto.EC_P256 -> NativePasskeyKeyProfile.EC_P256
                PasskeyKeyProfileProto.UNSPECIFIED -> null
            }
            if (profile == null || !value.hasExpectedBufferSizes()) {
                malformed(operation)
            }
            val decoded = NativePasskeyKeyMaterial(
                profile = profile,
                privateKeyPkcs8 = value.privateKeyPkcs8,
                publicKeyX = value.publicKeyX,
                publicKeyY = value.publicKeyY,
                publicKeySpki = value.publicKeySpki,
            )
            ownershipTransferred = true
            decoded
        } finally {
            if (!ownershipTransferred) {
                value.scrub()
            }
        }
    }

    private fun PasskeyKeyMaterialProto.hasExpectedBufferSizes(): Boolean =
        privateKeyPkcs8.size in 1..MAX_PRIVATE_KEY_BYTES &&
            publicKeySpki.size in 1..MAX_PRIVATE_KEY_BYTES &&
            publicKeyX.size == P256_COORDINATE_BYTES &&
            publicKeyY.size == P256_COORDINATE_BYTES

    private fun PasskeyKeyMaterialProto.scrub() {
        privateKeyPkcs8.fill(0)
        publicKeyX.fill(0)
        publicKeyY.fill(0)
        publicKeySpki.fill(0)
    }

    private inline fun <reified T> decodePayload(
        operation: String,
        payload: ByteArray,
    ): T = try {
        ProtoBuf.decodeFromByteArray<T>(payload)
    } catch (_: SerializationException) {
        malformed(operation)
    } finally {
        payload.fill(0)
    }

    private fun malformed(operation: String): Nothing = throw NativeCryptoException(
        operation = operation,
        code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
    )
}

private fun NativePasskeyAlgorithm.toProto(): PasskeyAlgorithmProto = when (this) {
    NativePasskeyAlgorithm.ES256 -> PasskeyAlgorithmProto.ES256
}

private fun PasskeyKeyErrorProto.toPublic(
    operation: String,
): NativePasskeyKeyError = when (this) {
    PasskeyKeyErrorProto.MALFORMED -> NativePasskeyKeyError.MALFORMED
    PasskeyKeyErrorProto.UNSUPPORTED -> NativePasskeyKeyError.UNSUPPORTED
    PasskeyKeyErrorProto.RESOURCE_LIMIT -> NativePasskeyKeyError.RESOURCE_LIMIT
    PasskeyKeyErrorProto.UNSPECIFIED -> throw NativeCryptoException(
        operation = operation,
        code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
    )
}
