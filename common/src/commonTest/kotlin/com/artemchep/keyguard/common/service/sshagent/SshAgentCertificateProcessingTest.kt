package com.artemchep.keyguard.common.service.sshagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class SshAgentCertificateProcessingTest {
    @Test
    fun `unlocked discovery offers the certificate before its key`() = runTest {
        val processor = certificateTestProcessor(
            unlockedVaultSession(certifiedSecret(TEST_ED25519_USER_CERTIFICATE)),
        )

        assertEquals(
            listOf(
                TEST_ED25519_USER_CERTIFICATE to "ssh-ed25519-cert-v01@openssh.com",
                TEST_ED25519_PUBLIC_KEY to "ssh-ed25519",
            ),
            processor.listedKeys().map { it.publicKey to it.keyType },
        )
        assertEquals(listOf("Certified", "Certified"), processor.listedKeys().map { it.name })
    }

    @Test
    fun `discovery leaves out a certificate that does not sign in`() = runTest {
        val cases = listOf(
            "not yet valid" to (TEST_ED25519_BOUNDED_USER_CERTIFICATE to
                TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_AFTER - SSH_CERTIFICATE_CLOCK_SKEW_SECONDS - 1),
            "expired" to (TEST_ED25519_BOUNDED_USER_CERTIFICATE to
                TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_BEFORE + SSH_CERTIFICATE_CLOCK_SKEW_SECONDS),
            "host certificate" to (TEST_ED25519_HOST_CERTIFICATE to TEST_NOW),
            "malformed" to ("ssh-ed25519-cert-v01@openssh.com AAAA" to TEST_NOW),
        )
        for ((label, case) in cases) {
            val (certificate, now) = case
            val processor = certificateTestProcessor(
                unlockedVaultSession(certifiedSecret(certificate)),
                now = now,
            )
            assertEquals(listOf(TEST_ED25519_PUBLIC_KEY), processor.listedKeys().map { it.publicKey }, label)
        }

        val valid = certificateTestProcessor(
            unlockedVaultSession(certifiedSecret(TEST_ED25519_BOUNDED_USER_CERTIFICATE)),
            now = TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_AFTER,
        )
        assertEquals(2, valid.listedKeys().size)
    }

    @Test
    fun `discovery allows for a device clock that is slightly off`() = runTest {
        val cases = listOf(
            "clock behind" to TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_AFTER - 1,
            "clock ahead" to TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_BEFORE,
        )
        for ((label, now) in cases) {
            val processor = certificateTestProcessor(
                unlockedVaultSession(certifiedSecret(TEST_ED25519_BOUNDED_USER_CERTIFICATE)),
                now = now,
            )
            assertEquals(2, processor.listedKeys().size, label)
        }
    }

    @Test
    fun `an unknown certificate of a stored key is not signed`() = runTest {
        val processor = certificateTestProcessor(
            unlockedVaultSession(certifiedSecret(TEST_ED25519_USER_CERTIFICATE)),
        )

        val result = processor.signData(certificateSignRequest(TEST_ED25519_BOUNDED_USER_CERTIFICATE))

        assertIs<SshAgentRequestProcessor.SignDataResult.KeyNotFound>(result)
    }

    @Test
    fun `locked discovery offers the cached certificate before its key`() = runTest {
        // The catalog keeps the certificate without its comment.
        val cached = TEST_ED25519_USER_CERTIFICATE.substringBeforeLast(' ')
        val processor = certificateTestProcessor(
            vaultSession = lockedVaultSession,
            catalog = certifiedCatalog(certifiedRow(cached)),
        )

        assertEquals(
            listOf(cached, TEST_ED25519_PUBLIC_KEY),
            processor.listedKeys().map { it.publicKey },
        )
    }

    @Test
    fun `locked discovery offers the certificate of any cipher of its key`() = runTest {
        var approvedCipher: String? = null
        val processor = certificateTestProcessor(
            vaultSession = lockedVaultSession,
            catalog = certifiedCatalog(
                certifiedRow(certificate = null, cipherId = "plain", name = "Plain"),
                certifiedRow(TEST_ED25519_USER_CERTIFICATE),
            ),
            onApprovalRequest = { approvedCipher = it.cipherId; true },
        )

        assertEquals(
            listOf(
                TEST_ED25519_USER_CERTIFICATE to "Certified",
                TEST_ED25519_PUBLIC_KEY to "Plain",
            ),
            processor.listedKeys().map { it.publicKey to it.name },
        )
        // The approval does not unlock this test vault.
        val result = processor.signData(certificateSignRequest(TEST_ED25519_USER_CERTIFICATE))
        assertIs<SshAgentRequestProcessor.SignDataResult.VaultLocked>(result)
        assertEquals("certified", approvedCipher)
    }

    @Test
    fun `locked signing for a cached certificate asks to approve its key`() = runTest {
        var approvedCipher: String? = null
        val processor = certificateTestProcessor(
            vaultSession = lockedVaultSession,
            catalog = certifiedCatalog(certifiedRow(TEST_ED25519_USER_CERTIFICATE)),
            onApprovalRequest = { approvedCipher = it.cipherId; true },
        )

        val result = processor.signData(certificateSignRequest(TEST_ED25519_USER_CERTIFICATE))

        // The approval does not unlock this test vault.
        assertIs<SshAgentRequestProcessor.SignDataResult.VaultLocked>(result)
        assertEquals("certified", approvedCipher)
    }
}
