package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.util.toHex
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.Security

internal fun ensureBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
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

internal fun PGPSecretKey.extractPrivateKeyEmptyPassphrase(): PGPPrivateKey {
    val decryptor = JcePBESecretKeyDecryptorBuilder(
        JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(),
    )
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
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
