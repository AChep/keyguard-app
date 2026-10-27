package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.crypto.NativeSshCertificateParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/** Signs through the native library, with the native certificate parser. */
class SshAgentCertificateSigningTest {
    @Test
    fun `a certificate request is signed by the key it certifies`() = runTest {
        var approvedCipher: String? = null
        val processor = certificateTestProcessor(
            unlockedVaultSession(certifiedSecret(TEST_ED25519_USER_CERTIFICATE)),
            parser = NativeSshCertificateParser,
            onApprovalRequest = { approvedCipher = it.cipherId; true },
        )

        // The Android bridge sends the blob without the comment of the line.
        val credential = TEST_ED25519_USER_CERTIFICATE.substringBeforeLast(' ')
        val result = processor.signData(certificateSignRequest(credential))

        val success = assertIs<SshAgentRequestProcessor.SignDataResult.Success>(result)
        assertEquals("ssh-ed25519", success.response.algorithm)
        assertEquals(64, success.response.signature.size)
        assertEquals("certified", approvedCipher)
    }

    @Test
    fun `discovery leaves out a certificate of another key`() = runTest {
        val other = generateRsaSshKeyPair()
        val secret = certifiedSecret(TEST_ED25519_USER_CERTIFICATE).let {
            it.copy(
                sshKey = it.sshKey?.copy(
                    privateKey = other.privateKeyPem,
                    publicKey = other.publicKeyOpenSsh,
                ),
            )
        }
        val processor = certificateTestProcessor(
            unlockedVaultSession(secret),
            parser = NativeSshCertificateParser,
        )

        assertEquals(listOf(other.publicKeyOpenSsh), processor.listedKeys().map { it.publicKey })
        val result = processor.signData(certificateSignRequest(TEST_ED25519_USER_CERTIFICATE))
        assertIs<SshAgentRequestProcessor.SignDataResult.KeyNotFound>(result)
    }

    @Test
    fun `unlocked discovery offers the certificate of any cipher of its key`() = runTest {
        var approvedCipher: String? = null
        val processor = certificateTestProcessor(
            unlockedVaultSession(
                certifiedSecret(certificate = null, id = "plain", name = "Plain"),
                certifiedSecret(TEST_ED25519_USER_CERTIFICATE),
            ),
            parser = NativeSshCertificateParser,
            onApprovalRequest = { approvedCipher = it.cipherId; true },
        )

        assertEquals(
            listOf(
                TEST_ED25519_USER_CERTIFICATE to "Certified",
                TEST_ED25519_PUBLIC_KEY to "Plain",
            ),
            processor.listedKeys().map { it.publicKey to it.name },
        )
        val result = processor.signData(certificateSignRequest(TEST_ED25519_USER_CERTIFICATE))
        assertIs<SshAgentRequestProcessor.SignDataResult.Success>(result)
        assertEquals("certified", approvedCipher)
    }
}
