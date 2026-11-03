@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Metadata and normalized key material. Callers must clear [bundle] and [privateKeyDer] after use.
 * Names are RFC 2253; times are Unix seconds; [keyType] is empty for an unknown algorithm.
 */
public data class NativePkcs12Identity(
    val bundle: ByteArray,
    val privateKeyDer: ByteArray,
    val displayName: String,
    val subject: String,
    val issuer: String,
    val issuerCommonName: String,
    val notBefore: Long,
    val expiresAt: Long,
    val serialNumber: ByteArray,
    val sha256Fingerprint: ByteArray,
    val keyType: String,
)

public sealed interface NativePkcs12Result {
    public data class Success(val identity: NativePkcs12Identity) : NativePkcs12Result
    public data object PasswordRequired : NativePkcs12Result
    public data object InvalidPassword : NativePkcs12Result
    public data object Invalid : NativePkcs12Result
}

public object NativeCryptoPkcs12 {
    public const val MAX_BUNDLE_BYTES: Int = 4 * 1024 * 1024

    public fun process(
        data: ByteArray,
        password: String? = null,
        normalize: Boolean = true,
        outputPassword: String? = null,
        exportPrivateKey: Boolean = false,
    ): NativePkcs12Result {
        if (data.isEmpty() || data.size > MAX_BUNDLE_BYTES) return NativePkcs12Result.Invalid
        val passwordBytes = password?.encodeToByteArray()
        val outputPasswordBytes = outputPassword?.encodeToByteArray()
        val encoded = try {
            NativeCrypto.call(
                operationName = "pkcs12",
                operation = Pkcs12OperationProto(
                    Pkcs12RequestProto(data, passwordBytes, normalize, outputPasswordBytes, exportPrivateKey),
                ),
            ).requireBytes("pkcs12")
        } finally {
            passwordBytes?.fill(0)
            outputPasswordBytes?.fill(0)
        }
        val result = try {
            ProtoBuf.decodeFromByteArray(Pkcs12ResultProto.serializer(), encoded)
        } finally {
            encoded.fill(0)
        }
        return when (result.status) {
            0 -> NativePkcs12Result.Success(NativePkcs12Identity(
                bundle = result.normalizedPkcs12,
                privateKeyDer = result.privateKeyDer,
                displayName = result.displayName,
                subject = result.subject,
                issuer = result.issuer,
                issuerCommonName = result.issuerCommonName,
                notBefore = result.notBefore,
                expiresAt = result.expiresAt,
                serialNumber = result.serialNumber,
                sha256Fingerprint = result.sha256Fingerprint,
                keyType = result.keyType,
            ))
            1 -> NativePkcs12Result.PasswordRequired
            2 -> NativePkcs12Result.InvalidPassword
            else -> NativePkcs12Result.Invalid
        }
    }
}
