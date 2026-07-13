package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.GpgKeyImportServiceJvm
import com.artemchep.keyguard.crypto.GpgKeyMetadataResolverJvm
import com.artemchep.keyguard.crypto.GpgPublicKeyParserJvm
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayInputStream
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpgKeyImportServiceJvmTest {
    private val service = GpgKeyImportServiceJvm(
        publicKeyParser = GpgPublicKeyParserJvm(),
        metadataResolver = GpgKeyMetadataResolverJvm(),
    )

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `imports armored public key`() {
        val publicKeyArmored = secretRing(GpgTestKeyFixtures.CV25519)
            .toCertificate()
            .armored()

        val result = service.import(
            GpgKeyImportRequest(
                content = publicKeyArmored,
                fileName = "public.asc",
            ),
        )

        assertTrue(result is GpgKeyImportResult.Success, "expected Success, got $result")
        val key = result.gpgKey
        assertEquals("", key.privateKeyArmored)
        assertEquals(publicKeyArmored, key.publicKeyArmored)
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", key.fingerprint)
        assertTrue(key.metadata.keys.any { it.canSign })
        assertTrue(key.metadata.keys.any { it.canDecrypt })
    }

    @Test
    fun `rejects legacy V2 and V3 public keys`() {
        GpgLegacyKeyFixtures.versions.forEach { version ->
            val result = service.import(
                GpgKeyImportRequest(
                    content = GpgLegacyKeyFixtures.publicRing(version).armored(),
                    fileName = "legacy-public.asc",
                ),
            )
            assertEquals(
                GpgKeyImportResult.Error(GpgKeyImportError.UnsupportedFormat),
                result,
            )
        }
    }

    @Test
    fun `imports unencrypted private key with derived public key`() {
        val result = service.import(
            GpgKeyImportRequest(
                content = GpgTestKeyFixtures.CV25519,
                fileName = "private.asc",
            ),
        )

        assertTrue(result is GpgKeyImportResult.Success, "expected Success, got $result")
        val key = result.gpgKey
        assertTrue(key.privateKeyArmored.contains("BEGIN PGP PRIVATE KEY BLOCK"))
        assertTrue(key.publicKeyArmored.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", key.fingerprint)
        assertTrue(key.metadata.keys.any { it.canSign })
        assertTrue(key.metadata.keys.any { it.canDecrypt })
    }

    @Test
    fun `rejects legacy V2 and V3 private keys`() {
        GpgLegacyKeyFixtures.versions.forEach { version ->
            val privateKeyArmored = GpgLegacyKeyFixtures.secretRing(version).armored()
            val result = service.import(
                GpgKeyImportRequest(
                    content = privateKeyArmored,
                    fileName = "legacy-private.asc",
                ),
            )
            assertEquals(
                GpgKeyImportResult.Error(GpgKeyImportError.UnsupportedFormat),
                result,
            )
        }
    }

    @Test
    fun `encrypted private key asks for passphrase`() {
        val encrypted = encryptSecretRing(GpgTestKeyFixtures.CV25519, PASSPHRASE)

        val result = service.import(
            GpgKeyImportRequest(
                content = encrypted,
                fileName = "private.asc",
            ),
        )

        assertEquals(
            GpgKeyImportResult.NeedsPassphrase("OpenPGP"),
            result,
        )
    }

    @Test
    fun `encrypted private key rejects invalid passphrase`() {
        val encrypted = encryptSecretRing(GpgTestKeyFixtures.CV25519, PASSPHRASE)

        val result = service.import(
            GpgKeyImportRequest(
                content = encrypted,
                fileName = "private.asc",
                passphrase = "wrong-passphrase",
            ),
        )

        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase),
            result,
        )
    }

    @Test
    fun `encrypted private key imports as passwordless key after passphrase`() {
        val encrypted = encryptSecretRing(GpgTestKeyFixtures.CV25519, PASSPHRASE)

        val result = service.import(
            GpgKeyImportRequest(
                content = encrypted,
                fileName = "private.asc",
                passphrase = PASSPHRASE,
            ),
        )

        assertTrue(result is GpgKeyImportResult.Success, "expected Success, got $result")
        val key = result.gpgKey
        assertNotEquals(encrypted, key.privateKeyArmored)
        val importedRing = secretRing(key.privateKeyArmored)
        val firstSecretKey = importedRing.secretKeys.asSequence()
            .first { !it.isPrivateKeyEmpty }
        assertNotNull(firstSecretKey.extractPrivateKeyEmptyPassphrase())
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", key.fingerprint)
    }

    private fun encryptSecretRing(
        armored: String,
        passphrase: String,
    ): String {
        val ring = secretRing(armored)
        val digestProvider = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build()
        val decryptor = JcePBESecretKeyDecryptorBuilder(digestProvider)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(CharArray(0))
        val encryptor = JcePBESecretKeyEncryptorBuilder(
            SymmetricKeyAlgorithmTags.AES_256,
            digestProvider.get(HashAlgorithmTags.SHA256),
        )
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(passphrase.toCharArray())
        return PGPSecretKeyRing.copyWithNewPassword(
            ring,
            decryptor,
            encryptor,
        ).armored()
    }

    private fun secretRing(
        armored: String,
    ): PGPSecretKeyRing {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        return collection.keyRings.next()
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
