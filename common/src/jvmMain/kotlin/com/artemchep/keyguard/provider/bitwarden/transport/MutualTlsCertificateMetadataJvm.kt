package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.util.foundation.crypto.sha256
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.time.Instant

/**
 * Describes the certificate in the format of the native PKCS#12 reader.
 * The two agree on common certificates; a name with an attribute that RFC
 * 2253 does not know, or an unusual curve, may be printed differently.
 * [issuerName] is the common name of the issuer, if it has one.
 */
internal fun X509Certificate.toMutualTlsMetadata(
    displayName: String,
    issuerName: String?,
): MutualTlsCertificateMetadata {
    val subject = subjectX500Principal.name
    val issuer = issuerX500Principal.name
    return MutualTlsCertificateMetadata(
        displayName = displayName.ifBlank { subject },
        subject = subject,
        issuer = issuer,
        issuerName = issuerName?.takeIf { it.isNotBlank() } ?: issuer,
        validFrom = Instant.fromEpochMilliseconds(notBefore.time),
        expiresAt = Instant.fromEpochMilliseconds(notAfter.time),
        // The sign byte of the two's complement form is not a part of the number.
        serialNumber = serialNumber.abs().toByteArray()
            .let { bytes -> if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes }
            .toColonHex(),
        sha256Fingerprint = sha256(encoded).toColonHex(),
        keyType = publicKey.describeKeyType(),
    )
}

/** For example `RSA 2048` or `EC P-256`; empty for an algorithm without a known name. */
private fun PublicKey.describeKeyType(): String = when (this) {
    is RSAPublicKey -> "RSA ${modulus.bitLength()}"
    is ECPublicKey -> "EC ${nistCurveNameOrNull() ?: params.curve.field.fieldSize}"
    else -> algorithm.takeIf { it in edwardsAlgorithms }.orEmpty()
}

private fun ECPublicKey.nistCurveNameOrNull(): String? = try {
    val name = AlgorithmParameters.getInstance("EC")
        .apply { init(params) }
        .getParameterSpec(ECGenParameterSpec::class.java)
        .name
    nistCurveNames[name]
} catch (_: GeneralSecurityException) {
    null
}

// A provider reports a curve by one of its names or by its object identifier.
private val nistCurveNames = mapOf(
    "secp256r1" to "P-256",
    "prime256v1" to "P-256",
    "1.2.840.10045.3.1.7" to "P-256",
    "secp384r1" to "P-384",
    "1.3.132.0.34" to "P-384",
    "secp521r1" to "P-521",
    "1.3.132.0.35" to "P-521",
)

private val edwardsAlgorithms = setOf("Ed25519", "Ed448")
