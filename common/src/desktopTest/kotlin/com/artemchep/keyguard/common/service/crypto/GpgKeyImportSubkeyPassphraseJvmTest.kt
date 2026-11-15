package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.crypto.gpgKeyIdHex
import com.artemchep.keyguard.crypto.parseGpgSecretKeyRingCollection
import com.artemchep.keyguard.crypto.protect
import com.artemchep.keyguard.nativecrypto.OPEN_PGP_MAX_KEY_IMPORT_PASSPHRASES
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue 1684: a key whose subkey uses a different passphrase than its primary key. */
class GpgKeyImportSubkeyPassphraseJvmTest {
    private val service = NativeGpgKeyImportService

    private val sourceRing = parseGpgSecretKeyRingCollection(GpgTestKeyFixtures.CV25519).keyRings.next()
    private val primaryKeyId = sourceRing.publicKey.keyID.gpgKeyIdHex()
    private val subkeyKeyId = sourceRing.publicKeys.asSequence().drop(1).single().keyID.gpgKeyIdHex()

    private val content = sourceRing
        .protect { secretKey ->
            if (secretKey.isMasterKey) PRIMARY_PASSPHRASE else SUBKEY_PASSPHRASE
        }
        .armored()
        .encodeToByteArray()

    @Test
    fun `without passphrases every component is locked and labelled`() {
        val result = assertIs<GpgKeyImportResult.NeedsPassphrase>(
            service.import(GpgKeyImportRequest(content = content)),
        )

        assertEquals("OpenPGP", result.formatLabel)
        assertEquals(emptySet(), result.unlockingPassphraseIndexes)
        val (primary, subkey) = result.lockedComponents
        assertEquals(2, result.lockedComponents.size)
        assertTrue(primary.primary)
        assertEquals(primaryKeyId, primary.keyId)
        assertTrue(primary.canSign)
        // The [SC] primary key itself cannot encrypt, even though its certificate can.
        assertFalse(primary.canEncrypt)
        assertNotNull(primary.algorithm)
        assertFalse(subkey.primary)
        assertEquals(subkeyKeyId, subkey.keyId)
        assertTrue(subkey.canEncrypt)
        assertFalse(subkey.canSign)
        assertNotNull(subkey.algorithm)
    }

    @Test
    fun `primary passphrase leaves the subkey locked`() {
        val result = assertIs<GpgKeyImportResult.NeedsPassphrase>(
            service.import(
                GpgKeyImportRequest(
                    content = content,
                    passphrases = listOf(PRIMARY_PASSPHRASE),
                ),
            ),
        )

        assertEquals(setOf(0), result.unlockingPassphraseIndexes)
        val subkey = result.lockedComponents.single()
        assertFalse(subkey.primary)
        assertEquals(subkeyKeyId, subkey.keyId)
        assertTrue(subkey.canEncrypt)
    }

    @Test
    fun `subkey passphrase leaves the primary key locked`() {
        val result = assertIs<GpgKeyImportResult.NeedsPassphrase>(
            service.import(
                GpgKeyImportRequest(
                    content = content,
                    passphrases = listOf("wrong", SUBKEY_PASSPHRASE),
                ),
            ),
        )

        assertEquals(setOf(1), result.unlockingPassphraseIndexes)
        val primary = result.lockedComponents.single()
        assertTrue(primary.primary)
        assertEquals(primaryKeyId, primary.keyId)
        assertTrue(primary.canSign)
        assertFalse(primary.canEncrypt)
    }

    @Test
    fun `a passphrase that unlocks nothing is invalid`() {
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase),
            service.import(
                GpgKeyImportRequest(
                    content = content,
                    passphrases = listOf("wrong"),
                ),
            ),
        )
    }

    @Test
    fun `both passphrases import an unprotected private key`() {
        val result = assertIs<GpgKeyImportResult.Success>(
            service.import(
                GpgKeyImportRequest(
                    content = content,
                    passphrases = listOf(SUBKEY_PASSPHRASE, PRIMARY_PASSPHRASE),
                ),
            ),
        )

        val imported = parseGpgSecretKeyRingCollection(result.gpgKey.privateKeyArmored).keyRings.next()
        val secretKeys = imported.secretKeys.asSequence().toList()
        assertEquals(2, secretKeys.size)
        secretKeys.forEach { key ->
            assertEquals(SymmetricKeyAlgorithmTags.NULL, key.keyEncryptionAlgorithm)
            assertNotNull(key.extractPrivateKeyEmptyPassphrase())
        }
    }

    @Test
    fun `too many passphrases do not throw`() {
        val passphrases = List(OPEN_PGP_MAX_KEY_IMPORT_PASSPHRASES + 1) { "p$it" }
        assertEquals(
            GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase),
            service.import(GpgKeyImportRequest(content = content, passphrases = passphrases)),
        )
    }
}

private const val PRIMARY_PASSPHRASE = "primary passphrase"
private const val SUBKEY_PASSPHRASE = "subkey passphrase"
