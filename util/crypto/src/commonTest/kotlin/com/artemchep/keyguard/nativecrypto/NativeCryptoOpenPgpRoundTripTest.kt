package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Functional coverage formerly embedded in the production desktop package smoke. */
class NativeCryptoOpenPgpRoundTripTest {
    @Test
    fun streamedDetachedSignatureVerifies() = withMaterial { material ->
        val plaintext = "native detached signing".encodeToByteArray()
        val signature = NativeCrypto.openPgp.openDetachedSigning(
            privateKey = material.privateKeyArmored,
            candidateRevocationKeys = emptyList(),
            armored = false,
        ).use { session ->
            session.update(plaintext, offset = 0, length = 3)
            session.update(plaintext, offset = 3, length = plaintext.size - 3)
            session.finish()
        }
        val verification = NativeCrypto.openPgp.verifyDetached(
            content = plaintext,
            signature = signature,
            publicKeys = listOf(material.publicKeyArmored),
        )
        assertEquals(NativeOpenPgpVerificationStatus.VALID, verification.status)
    }

    @Test
    fun cleartextSignatureVerifies() = withMaterial { material ->
        val signed = NativeCrypto.openPgp.clearSign(
            content = "First line\n- Dash-escaped line\n".encodeToByteArray(),
            privateKey = material.privateKeyArmored,
            candidateRevocationKeys = emptyList(),
        )
        assertEquals(
            NativeOpenPgpVerificationStatus.VALID,
            NativeCrypto.openPgp.verifyClearSigned(signed, listOf(material.publicKeyArmored)).status,
        )
    }

    @Test
    fun mixedVersionsDecryptWithEitherRecipient() {
        val materials = NativeOpenPgpKeyVersion.entries.map(::generateMaterial)
        try {
            val plaintext = "Shared v4 and v6 message".encodeToByteArray()
            val encrypted = NativeCrypto.openPgp.encrypt(
                content = plaintext,
                publicKeys = materials.map { it.publicKeyArmored },
                candidateRevocationKeys = emptyList(),
                fileName = "mixed.txt",
                armored = false,
            )
            materials.forEach { material ->
                val decrypted = NativeCrypto.openPgp.decrypt(
                    content = encrypted.data,
                    privateKeys = listOf(material.privateKeyArmored),
                )
                assertContentEquals(plaintext, decrypted.data)
            }
        } finally {
            materials.forEach { it.privateKeyArmored.fill(0) }
        }
    }

    @Test
    fun signedOcbEncryptionAuthenticatesAfterStreamingRoundTrip() = withMaterial { material ->
        val plaintext = "native signed encryption".encodeToByteArray()
        val encryptedChunks = mutableListOf<ByteArray>()
        NativeCrypto.openPgp.openEncryption(
            publicKeys = listOf(material.publicKeyArmored),
            candidateRevocationKeys = emptyList(),
            signingPrivateKey = material.privateKeyArmored,
            fileName = "probe.txt",
            armored = false,
        ).use { session ->
            encryptedChunks += session.update(plaintext, offset = 0, length = 3)
            encryptedChunks += session.update(plaintext, offset = 3, length = plaintext.size - 3)
            val final = session.finish()
            assertEquals(
                if (material.fingerprint.length == 64) NativeOpenPgpProtectionMode.SEIPD_V2_AEAD
                else NativeOpenPgpProtectionMode.GNUPG_OCB,
                final.protectionMode,
            )
            encryptedChunks += final.data
        }
        val ciphertext = encryptedChunks.fold(byteArrayOf()) { result, chunk -> result + chunk }
        val decryptedChunks = mutableListOf<ByteArray>()
        NativeCrypto.openPgp.openDecryption(
            privateKeys = listOf(material.privateKeyArmored),
            verificationPublicKeys = listOf(material.publicKeyArmored),
        ).use { session ->
            val split = ciphertext.size / 2
            decryptedChunks += session.update(ciphertext, offset = 0, length = split)
            decryptedChunks += session.update(ciphertext, offset = split, length = ciphertext.size - split)
            val final = session.finish()
            assertEquals(NativeOpenPgpVerificationStatus.VALID, final.verification?.status)
            decryptedChunks += final.data
        }
        assertContentEquals(plaintext, decryptedChunks.fold(byteArrayOf()) { result, chunk -> result + chunk })
    }

    @Test
    fun agentRejectsAnUnknownDecryptionFingerprint() = withMaterial { material ->
        val result = NativeCrypto.openPgp.agentDecrypt(
            privateKey = material.privateKeyArmored,
            preferredFingerprint = "0000000000000000000000000000000000000000",
            ciphertext = byteArrayOf(),
            unwrapEcdh = false,
        )
        assertEquals(NativeOpenPgpAgentDecryptResult.Error(NativeOpenPgpAgentError.KEY_NOT_FOUND), result)
    }

    private fun withMaterial(block: (NativeOpenPgpKeyMaterial) -> Unit) {
        NativeOpenPgpKeyVersion.entries.forEach { version ->
            val material = generateMaterial(version)
            try {
                block(material)
            } finally {
                material.privateKeyArmored.fill(0)
                material.publicKeyArmored.fill(0)
            }
        }
    }

    private fun generateMaterial(version: NativeOpenPgpKeyVersion) = NativeCrypto.openPgp.generateKey(
        kind = if (version == NativeOpenPgpKeyVersion.V4) NativeOpenPgpKeyKind.LEGACY_ED25519_X25519
        else NativeOpenPgpKeyKind.ED25519_X25519,
        version = version,
        userId = "Native round trip <native-round-trip@test.invalid>",
        creationTimeEpochSeconds = 1_700_000_000L,
    )
}
