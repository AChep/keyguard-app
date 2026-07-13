package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.util.toHex
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.Provider

/**
 * The GPG stack talks to BouncyCastle directly through this single [Provider] instance and never
 * through the global [java.security.Security] registry.
 */
internal val gpgBouncyCastleProvider: Provider by lazy {
    BouncyCastleProvider()
}

internal fun PGPKeyRing.armored(): String {
    val out = ByteArrayOutputStream()
    ArmoredOutputStream(out).use { armoredOut ->
        encode(armoredOut)
    }
    return out.toString(Charsets.UTF_8)
}

internal fun PGPPublicKey.fingerprintHex(): String =
    fingerprint.toHex().uppercase()

internal fun PGPSecretKey.fingerprintHex(): String =
    publicKey.fingerprintHex()

internal fun Long.gpgKeyIdHex(): String =
    toULong().toString(16).uppercase().padStart(16, '0')

internal fun parseGpgPublicKeyRingCollection(
    armored: String,
): PGPPublicKeyRingCollection {
    val collection = PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
        JcaKeyFingerprintCalculator(),
    )
    collection.keyRings
        .asSequence()
        .flatMap { ring -> ring.publicKeys.asSequence() }
        .requireSupportedGpgKeyVersions()
    return collection
}

internal fun parseGpgSecretKeyRingCollection(
    armored: String,
): PGPSecretKeyRingCollection {
    val collection = PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
        JcaKeyFingerprintCalculator(),
    )
    collection.keyRings
        .asSequence()
        .flatMap { ring -> ring.publicKeys.asSequence() }
        .requireSupportedGpgKeyVersions()
    return collection
}

internal fun PGPPublicKey.isSupportedGpgKeyVersion(): Boolean =
    version > PublicKeyPacket.VERSION_3

private fun Sequence<PGPPublicKey>.requireSupportedGpgKeyVersions() {
    val legacyKey = firstOrNull { key -> !key.isSupportedGpgKeyVersion() }
        ?: return
    throw GpgUnsupportedKeyVersionException(legacyKey.version)
}

internal class GpgUnsupportedKeyVersionException(
    val version: Int,
) : IllegalArgumentException("OpenPGP V2/V3 keys are not supported.")

/**
 * Parses independent public-key candidates without letting one malformed vault item poison the
 * whole candidate set. Callers still authenticate every use of these keys cryptographically.
 */
internal fun Iterable<GpgOpenPgpPublicKey>.parseGpgPublicKeyCandidates(): List<PGPPublicKey> =
    flatMap { candidate ->
        if (candidate.armored.isBlank()) {
            return@flatMap emptyList()
        }
        try {
            parseGpgPublicKeyRingCollection(candidate.armored)
                .keyRings
                .asSequence()
                .flatMap { ring -> ring.publicKeys.asSequence() }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }.distinctBy { key ->
        key.algorithm to key.fingerprintHex()
    }

internal fun PGPSecretKey.extractPrivateKeyEmptyPassphrase(): PGPPrivateKey {
    val decryptor = JcePBESecretKeyDecryptorBuilder(
        JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(gpgBouncyCastleProvider)
            .build(),
    )
        .setProvider(gpgBouncyCastleProvider)
        .build(CharArray(0))
    return extractPrivateKey(decryptor)
}

internal fun ByteArray.stripUnsignedMagnitudePadding(): ByteArray {
    var index = 0
    while (index < size - 1 && this[index] == 0.toByte()) {
        index++
    }
    return copyOfRange(index, size)
}

internal fun BigInteger.toUnsignedBytes(): ByteArray =
    toByteArray().stripUnsignedMagnitudePadding()
