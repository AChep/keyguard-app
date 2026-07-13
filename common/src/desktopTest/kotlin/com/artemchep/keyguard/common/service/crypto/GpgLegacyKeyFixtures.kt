package com.artemchep.keyguard.common.service.crypto

import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import java.security.KeyPairGenerator
import java.util.Date

internal object GpgLegacyKeyFixtures {
    fun publicRing(version: Int): PGPPublicKeyRing = secretRing(version).toCertificate()

    fun publicSubkey(version: Int): PGPPublicKey = createKeyPair(version)
        .asSubkey(JcaKeyFingerprintCalculator())
        .publicKey

    fun secretRing(version: Int): PGPSecretKeyRing = rings.getValue(version)

    private val rings: Map<Int, PGPSecretKeyRing> by lazy {
        LEGACY_VERSIONS.associateWith(::createSecretRing)
    }

    private fun createSecretRing(version: Int): PGPSecretKeyRing {
        val pgpKeyPair = createKeyPair(version)
        val secretKey = PGPSecretKey(
            pgpKeyPair.privateKey,
            pgpKeyPair.publicKey,
            null,
            true,
            null,
        )
        return PGPSecretKeyRing(listOf(secretKey))
    }

    private fun createKeyPair(version: Int): PGPKeyPair {
        require(version in LEGACY_VERSIONS)
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
        return JcaPGPKeyPair(
            version,
            PublicKeyAlgorithmTags.RSA_GENERAL,
            keyPair,
            Date(CREATION_TIME_MILLIS),
        )
    }

    val versions: List<Int>
        get() = LEGACY_VERSIONS

    private const val CREATION_TIME_MILLIS = 1_700_000_000_000L
    private val LEGACY_VERSIONS = listOf(2, 3)
}
