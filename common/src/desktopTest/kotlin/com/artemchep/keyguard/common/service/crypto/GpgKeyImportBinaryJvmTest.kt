package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.crypto.parseGpgSecretKeyRingCollection
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GpgKeyImportBinaryJvmTest {
    private val service = NativeGpgKeyImportService

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `imports binary public key with the same certificate as armored input`() {
        val ring = secretRing(GpgTestKeyFixtures.CV25519).toCertificate()
        val content = ring.encoded
        val original = content.copyOf()
        val result = assertIs<GpgKeyImportResult.Success>(
            service.import(GpgKeyImportRequest(content = content, fileName = "public.gpg")),
        )
        val armored = assertIs<GpgKeyImportResult.Success>(
            service.import(GpgKeyImportRequest(content = ring.armored().encodeToByteArray())),
        )

        assertEquals(armored.gpgKey.fingerprint, result.gpgKey.fingerprint)
        assertEquals(armored.gpgKey.publicKeyArmored, result.gpgKey.publicKeyArmored)
        assertEquals("", result.gpgKey.privateKeyArmored)
        assertContentEquals(original, content)
    }

    @Test
    fun `imports binary private key with the same material as armored input`() {
        val ring = secretRing(GpgTestKeyFixtures.CV25519)
        val content = ring.encoded
        val original = content.copyOf()
        val result = assertIs<GpgKeyImportResult.Success>(
            service.import(GpgKeyImportRequest(content = content, fileName = "private.gpg")),
        )
        val armored = assertIs<GpgKeyImportResult.Success>(
            service.import(GpgKeyImportRequest(content = ring.armored().encodeToByteArray())),
        )

        assertEquals(armored.gpgKey.fingerprint, result.gpgKey.fingerprint)
        assertEquals(armored.gpgKey.publicKeyArmored, result.gpgKey.publicKeyArmored)
        assertEquals(armored.gpgKey.privateKeyArmored, result.gpgKey.privateKeyArmored)
        assertContentEquals(original, content)
    }

    @Test
    fun `binary protected private key survives passphrase retries`() {
        val ring = secretRing(encryptGpgImportTestSecretRing(GpgTestKeyFixtures.CV25519, PASSPHRASE))
        val content = ring.encoded
        val original = content.copyOf()
        val request = GpgKeyImportRequest(content = content, fileName = "private.gpg")

        assertEquals(GpgKeyImportResult.NeedsPassphrase("OpenPGP"), service.import(request))
        assertContentEquals(original, content)
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase),
            service.import(request.copy(passphrase = "wrong-passphrase")),
        )
        assertContentEquals(original, content)
        val result = assertIs<GpgKeyImportResult.Success>(
            service.import(request.copy(passphrase = PASSPHRASE)),
        )

        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", result.gpgKey.fingerprint)
        val imported = secretRing(result.gpgKey.privateKeyArmored)
        imported.secretKeys.asSequence().filterNot { it.isPrivateKeyEmpty }.forEach { key ->
            assertEquals(SymmetricKeyAlgorithmTags.NULL, key.keyEncryptionAlgorithm)
            assertNotNull(key.extractPrivateKeyEmptyPassphrase())
        }
        assertContentEquals(original, content)
    }

    @Test
    fun `empty and whitespace input remain empty`() {
        for (content in listOf(byteArrayOf(), " \t\r\n".encodeToByteArray())) {
            assertEquals(
                GpgKeyImportResult.Error(GpgKeyImportError.Empty),
                service.import(GpgKeyImportRequest(content = content)),
            )
        }
    }

    @Test
    fun `imports armored keys surrounded by whitespace`() {
        val content = " \t\r\n${GpgTestKeyFixtures.CV25519}\r\n \t".encodeToByteArray()
        val result = assertIs<GpgKeyImportResult.Success>(
            service.import(GpgKeyImportRequest(content = content)),
        )
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", result.gpgKey.fingerprint)
    }

    @Test
    fun `truncated binary input remains malformed and unchanged`() {
        val content = secretRing(GpgTestKeyFixtures.CV25519).encoded.copyOf(16)
        val original = content.copyOf()
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey),
            service.import(GpgKeyImportRequest(content = content)),
        )
        assertContentEquals(original, content)
    }
}

private const val PASSPHRASE = "correct horse battery staple"

private fun secretRing(armored: String): PGPSecretKeyRing =
    parseGpgSecretKeyRingCollection(armored).keyRings.next()
