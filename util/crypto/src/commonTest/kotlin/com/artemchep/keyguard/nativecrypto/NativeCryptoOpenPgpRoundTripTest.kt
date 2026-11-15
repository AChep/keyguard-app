package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Functional coverage formerly embedded in the production desktop package smoke. */
class NativeCryptoOpenPgpRoundTripTest {
    @Test
    fun signedMessageRoundTripPreservesAuthenticatedMetadata() = withOpenPgpTestMaterial { material ->
        val plaintext = "signed message with metadata".encodeToByteArray()
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = plaintext,
            publicKeys = listOf(material.publicKeyArmored),
            candidateRevocationKeys = emptyList(),
            signingPrivateKey = material.privateKeyArmored,
            fileName = "message.txt",
            armored = false,
        )
        val decrypted = NativeCrypto.openPgp.decrypt(
            content = encrypted.data,
            privateKeys = listOf(material.privateKeyArmored),
            verificationPublicKeys = listOf(material.publicKeyArmored),
        )
        assertContentEquals(plaintext, decrypted.data)
        assertEquals(NativeOpenPgpVerificationStatus.VALID, decrypted.verification?.status)
        assertTrue(decrypted.encrypted)
        assertContentEquals("message.txt".encodeToByteArray(), decrypted.metadata?.fileName)
        assertEquals(plaintext.size.toLong(), decrypted.metadata?.originalSize)
        assertTrue(decrypted.decryptionKeyFingerprint?.isNotEmpty() == true)
        assertTrue(decrypted.warnings.isEmpty())
    }

    @Test
    fun tamperedCiphertextDoesNotReturnPlaintext() = withOpenPgpTestMaterial { material ->
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = "authenticated message".encodeToByteArray(),
            publicKeys = listOf(material.publicKeyArmored),
            candidateRevocationKeys = emptyList(),
            fileName = "",
            armored = false,
        )
        val tampered = encrypted.data.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val failure = assertFailsWith<NativeCryptoException> {
            NativeCrypto.openPgp.decrypt(tampered, listOf(material.privateKeyArmored))
        }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
    }

    @Test
    fun streamedDetachedSignatureVerifies() = withOpenPgpTestMaterial { material ->
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
    fun cleartextSignatureVerifies() = withOpenPgpTestMaterial { material ->
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
        val materials = NativeOpenPgpKeyVersion.entries.map(::generateOpenPgpTestMaterial)
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
    fun agentRejectsAnUnknownDecryptionFingerprint() = withOpenPgpTestMaterial { material ->
        val result = NativeCrypto.openPgp.agentDecrypt(
            privateKey = material.privateKeyArmored,
            preferredFingerprint = "0000000000000000000000000000000000000000",
            ciphertext = byteArrayOf(),
            unwrapEcdh = false,
        )
        assertEquals(NativeOpenPgpAgentDecryptResult.Error(NativeOpenPgpAgentError.KEY_NOT_FOUND), result)
    }

}

internal fun withOpenPgpTestMaterial(block: (NativeOpenPgpKeyMaterial) -> Unit) {
    NativeOpenPgpKeyVersion.entries.forEach { version ->
        val material = generateOpenPgpTestMaterial(version)
        try {
            block(material)
        } finally {
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }
}

internal fun generateOpenPgpTestMaterial(version: NativeOpenPgpKeyVersion) = NativeCrypto.openPgp.generateKey(
    kind = if (version == NativeOpenPgpKeyVersion.V4) NativeOpenPgpKeyKind.LEGACY_ED25519_X25519
    else NativeOpenPgpKeyKind.ED25519_X25519,
    version = version,
    userId = "Native round trip <native-round-trip@test.invalid>",
    creationTimeEpochSeconds = 1_700_000_000L,
)
