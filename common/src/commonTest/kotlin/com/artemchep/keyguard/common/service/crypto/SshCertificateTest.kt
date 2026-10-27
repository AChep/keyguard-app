package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_PUBLIC_KEY
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_USER_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.testSshCertificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshCertificateTest {
    @Test
    fun `a user certificate of the key signs in`() {
        assertNull(testSshCertificate().problemAt(NOW))
    }

    @Test
    fun `the validity window includes its start and excludes its end`() {
        val certificate = testSshCertificate(
            validAfter = VALID_AFTER,
            validBefore = VALID_BEFORE,
        )
        assertEquals(SshCertificateProblem.NotYetValid(VALID_AFTER), certificate.problemAt(VALID_AFTER - 1))
        assertNull(certificate.problemAt(VALID_AFTER))
        assertNull(certificate.problemAt(VALID_BEFORE - 1))
        assertEquals(SshCertificateProblem.Expired(VALID_BEFORE), certificate.problemAt(VALID_BEFORE))
    }

    @Test
    fun `problems that do not depend on time`() {
        val host = testSshCertificate(type = SshCertificate.Type.Host)
        assertEquals(SshCertificateProblem.HostCertificate, host.problemAt(NOW))

        val mismatch = testSshCertificate(publicKeyMatches = false)
        assertEquals(SshCertificateProblem.KeyMismatch, mismatch.problemAt(NOW))

        val malformed: SshCertificate? = null
        assertEquals(SshCertificateProblem.Malformed, malformed.problemAt(NOW))
    }

    @Test
    fun `without a key the certificate is not checked against one`() {
        val usable = testSshCertificate(publicKeyMatches = false)
        assertNull(usable.problemAt(NOW, hasPublicKey = false))

        val bounded = testSshCertificate(
            validAfter = VALID_AFTER,
            validBefore = VALID_BEFORE,
            publicKeyMatches = false,
        )
        assertEquals(
            SshCertificateProblem.Expired(VALID_BEFORE),
            bounded.problemAt(VALID_BEFORE, hasPublicKey = false),
        )

        val host = testSshCertificate(
            type = SshCertificate.Type.Host,
            publicKeyMatches = false,
        )
        assertEquals(SshCertificateProblem.HostCertificate, host.problemAt(NOW, hasPublicKey = false))
    }

    @Test
    fun `a certificate file is told apart from a key file by its first word`() {
        assertTrue(isSshCertificateFile("\n\t$TEST_ED25519_USER_CERTIFICATE".encodeToByteArray()))
        assertFalse(isSshCertificateFile(TEST_ED25519_PUBLIC_KEY.encodeToByteArray()))
        assertFalse(isSshCertificateFile("-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray()))
        assertFalse(isSshCertificateFile(" \n".encodeToByteArray()))
        assertFalse(isSshCertificateFile(ByteArray(0)))
    }

    private companion object {
        // 2026-01-01T00:00:00Z
        const val NOW = 1_767_225_600L

        // 2022-04-09T14:35:39Z to 2100-03-05T14:35:39Z.
        const val VALID_AFTER = 1_649_514_939L
        const val VALID_BEFORE = 4_107_940_539L
    }
}
