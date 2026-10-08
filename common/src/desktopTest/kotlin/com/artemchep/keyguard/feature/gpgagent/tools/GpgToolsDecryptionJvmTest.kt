package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.GpgKeyVersion
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpLiteralFileName
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpService
import com.artemchep.keyguard.test.createSecret
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class GpgToolsDecryptionJvmTest {
    private val service = NativeGpgOpenPgpService()

    @Test
    fun `native message decryption supports v6 keys without agent decryption capability`() {
        assertFalse(assertNotNull(v6.metadata).routableAgentKeys.any { it.canDecrypt })
        assertTextRoundTrip(v6, listOf(v6.toResolvedKey().toPrivateKey()))
    }

    @Test
    fun `tools decrypt v4 text with automatic key selection`() {
        assertTextRoundTrip(v4, mixedVault().resolveDecryptKeys())
    }

    @Test
    fun `tools decrypt v6 text when an unrelated v4 key is also stored`() {
        assertTextRoundTrip(v6, mixedVault().resolveDecryptKeys())
    }

    @Test
    fun `tools decrypt armored and binary v6 files when an unrelated v4 key is stored`() {
        val plaintext = ByteArray(128 * 1024 + 17) { (it % 251).toByte() }
        for (armored in listOf(true, false)) {
            val encrypted = Buffer()
            service.encryptFile(GpgOpenPgpEncryptFileRequest(
                input = Buffer().apply { write(plaintext) },
                output = encrypted,
                publicKeys = listOf(GpgOpenPgpPublicKey(v6.publicKeyArmored)),
                candidateRevocationKeys = emptyList(),
                fileName = GpgOpenPgpLiteralFileName.fromUntrusted("d14.bin"),
                armored = armored,
            ))
            val decrypted = Buffer()
            val result = service.decryptFile(GpgOpenPgpReadFileRequest(
                input = encrypted,
                output = decrypted,
                privateKeys = mixedVault().resolveDecryptKeys(),
            ))
            assertContentEquals(plaintext, decrypted.readByteArray())
            assertEquals(v6.decryptionFingerprint(), result.decryptionKeyFingerprint)
        }
    }

    @Test
    fun `tools decrypt with only a v6 key and no agent authorization`() {
        assertTextRoundTrip(v6, listOf(v6.toResolvedKey()).resolveDecryptKeys())
    }

    @Test
    fun `public only and blank private material are excluded from decryption candidates`() {
        val privateKey = v6.toResolvedKey()
        val keys = listOf(
            privateKey.copy(id = "public-only", privateKeyArmored = null),
            privateKey.copy(id = "blank", privateKeyArmored = "  \n"),
            privateKey,
        ).resolveDecryptKeys()
        assertEquals(listOf(privateKey.toPrivateKey()), keys)
    }

    @Test
    fun `vault without private material fails before calling native crypto`() {
        assertFailsWith<IllegalStateException> {
            listOf(v6.toResolvedKey().copy(privateKeyArmored = null)).resolveDecryptKeys()
        }
    }

    private fun assertTextRoundTrip(
        recipient: GeneratedGpgKey,
        privateKeys: List<GpgOpenPgpPrivateKey>,
    ) {
        val plaintext = "D14 generated-key regression: українська 🔑"
        val encrypted = service.encryptText(GpgOpenPgpEncryptTextRequest(
            text = plaintext,
            publicKeys = listOf(GpgOpenPgpPublicKey(recipient.publicKeyArmored)),
            candidateRevocationKeys = emptyList(),
        ))
        val decrypted = service.decryptText(GpgOpenPgpDecryptTextRequest(
            encryptedText = encrypted,
            privateKeys = privateKeys,
        ))
        assertEquals(plaintext, decrypted.text)
        assertEquals(recipient.decryptionFingerprint(), decrypted.decryptionKeyFingerprint)
    }

    private fun GeneratedGpgKey.decryptionFingerprint(): String =
        assertNotNull(metadata).certificates.single().components.single {
            // Legacy ECDH (v4) or native X25519 (v6).
            it.publicKeyAlgorithmId in setOf(18, 25)
        }.fingerprint

    private fun mixedVault() = listOf(v4.toResolvedKey(), v6.toResolvedKey())

    private fun GeneratedGpgKey.toResolvedKey(): ResolvedGpgKey = GpgAgentSecret(
        cipher = createSecret(id = fingerprint),
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = assertNotNull(metadata),
    ).toResolvedGpgKey()

    companion object {
        private val v4 by lazy { generate(GpgKeyVersion.V4) }
        private val v6 by lazy { generate(GpgKeyVersion.V6) }

        private fun generate(version: GpgKeyVersion) = NativeGpgKeyGenerator.generate(
            GpgKeyConfig.Modern(
                userId = "D14 regression <d14@test.invalid>",
                version = version,
            ),
        )
    }
}
