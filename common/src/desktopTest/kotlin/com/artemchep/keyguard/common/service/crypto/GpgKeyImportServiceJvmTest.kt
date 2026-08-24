package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.gpgagent.authorizedAgentKeys
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.canonicalGpgArmorForComparison
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyParseResult
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpgKeyImportServiceJvmTest {
    private val service = NativeGpgKeyImportService

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
        assertEquals(
            publicKeyArmored.canonicalGpgArmorForComparison(),
            key.publicKeyArmored.canonicalGpgArmorForComparison(),
        )
        assertFalse(key.publicKeyArmored.contains("Version: BCPG"))
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", key.fingerprint)
        val resolution = assertNotNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = key.privateKeyArmored,
                publicKeyArmored = key.publicKeyArmored,
                fingerprint = key.fingerprint,
            ),
        )
        assertTrue(resolution.authorizedAgentKeys.isEmpty())
        assertTrue(resolution.metadata.routableAgentKeys.any { it.canSign })
        assertTrue(resolution.metadata.routableAgentKeys.any { it.canDecrypt })
    }

    @Test
    fun `rejects public key documents containing multiple certificates`() {
        val first = secretRing(GpgTestKeyFixtures.CV25519)
            .toCertificate()
            .armored()
        val second = NativeGpgKeyGenerator.generate(
            GpgKeyConfig.Modern(userId = "Second Key <second@test.invalid>"),
        ).publicKeyArmored
        val document = publicKeyDocument(first, second)

        val parsed = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(document),
        )
        assertEquals(2, parsed.keys.size)
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.MultipleKeys),
            service.import(
                GpgKeyImportRequest(
                    content = document,
                    fileName = "multiple-public.asc",
                ),
            ),
        )
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
                "legacy public key version $version",
            )
        }
    }

    @Test
    fun `reports a skipped legacy certificate and rejects the mixed import`() {
        val modern = NativeGpgKeyGenerator.generate(
            GpgKeyConfig.Modern(userId = "Mixed Document <mixed@test.invalid>"),
        )
        val modernRing = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(
                ByteArrayInputStream(modern.publicKeyArmored.encodeToByteArray()),
            ),
            JcaKeyFingerprintCalculator(),
        ).keyRings.next()
        // A keyserver export can carry a v3 certificate alongside usable ones in one
        // armored block. Skipping the unsupported certificate must not discard the rest
        // of the document.
        val document = ByteArrayOutputStream().also { out ->
            ArmoredOutputStream(out).use { armored ->
                armored.write(GpgLegacyKeyFixtures.publicRing(3).encoded)
                armored.write(modernRing.encoded)
            }
        }.toString(Charsets.UTF_8)

        val parsed = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(document),
        )
        assertEquals(listOf(modern.fingerprint), parsed.keys.map { key -> key.fingerprint })
        assertEquals(1, parsed.skippedCertificates)

        // The native layer reports how many certificates it had to drop.
        val native = NativeCrypto.openPgp.parsePublicKeys(document.encodeToByteArray())
        assertEquals(1, assertIs<NativeOpenPgpPublicKeyParseResult.Success>(native).skippedCertificates)
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.MultipleKeys),
            service.import(
                GpgKeyImportRequest(
                    content = document,
                    fileName = "mixed-public.asc",
                ),
            ),
        )
    }

    @Test
    fun `garbage private key remains malformed`() {
        val result = service.import(
            GpgKeyImportRequest(
                content = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\ntruncated",
                fileName = "malformed-private.asc",
            ),
        )

        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey),
            result,
        )
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
        val authorization = assertNotNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = key.privateKeyArmored,
                publicKeyArmored = key.publicKeyArmored,
                fingerprint = key.fingerprint,
            ),
        )
        assertTrue(authorization.authorizedAgentKeys.any { it.canSign })
        assertTrue(authorization.authorizedAgentKeys.any { it.canDecrypt })
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
                "legacy private key version $version",
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

    private fun publicKeyDocument(
        vararg armoredKeys: String,
    ): String = ByteArrayOutputStream().also { out ->
        ArmoredOutputStream(out).use { armored ->
            armoredKeys.forEach { publicKeyArmored ->
                val collection = PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(
                        ByteArrayInputStream(publicKeyArmored.encodeToByteArray()),
                    ),
                    JcaKeyFingerprintCalculator(),
                )
                armored.write(collection.keyRings.next().encoded)
            }
        }
    }.toString(Charsets.UTF_8)

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
