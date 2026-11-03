package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.SshCertificate
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_BOUNDED_USER_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_HOST_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_PUBLIC_KEY
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_USER_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TestSshCertificateParser
import com.artemchep.keyguard.common.service.sshagent.generateRsaSshKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSshCertificateParserTest {
    @Test
    fun `reads a user certificate of the key`() {
        val certificate = NativeSshCertificateParser.parse(
            certificate = "\n$TEST_ED25519_USER_CERTIFICATE\n",
            publicKey = TEST_ED25519_PUBLIC_KEY,
        )

        assertNotNull(certificate)
        assertEquals(SshCertificate.Type.User, certificate.type)
        assertEquals("keyguard-ed25519-user-forever", certificate.keyId)
        assertEquals(0uL, certificate.serial)
        assertEquals(listOf("test-user"), certificate.principals)
        assertEquals(0L, certificate.validAfter)
        assertEquals(Long.MAX_VALUE, certificate.validBefore)
        assertEquals("SHA256:jTLGWbD4UG+AGexRMvU0a1/TJtuiUI597k4ZJzE4/8U", certificate.caFingerprint)
        assertEquals(emptyList(), certificate.criticalOptions)
        assertEquals(
            SshCertificate.Option(name = "permit-pty", value = ""),
            certificate.extensions.first { it.name == "permit-pty" },
        )
        assertTrue(certificate.publicKeyMatches)
    }

    @Test
    fun `reports whether the certificate certifies the key`() {
        val otherKey = generateRsaSshKeyPair().publicKeyOpenSsh
        val mismatch = NativeSshCertificateParser.parse(TEST_ED25519_USER_CERTIFICATE, otherKey)
        assertFalse(assertNotNull(mismatch).publicKeyMatches)

        val withoutKey = NativeSshCertificateParser.parse(TEST_ED25519_USER_CERTIFICATE, publicKey = null)
        assertFalse(assertNotNull(withoutKey).publicKeyMatches)
    }

    @Test
    fun `returns null for anything that is not a certificate`() {
        for (malformed in listOf(TEST_ED25519_PUBLIC_KEY, "ssh-ed25519-cert-v01@openssh.com AAAA", " ", "")) {
            assertNull(NativeSshCertificateParser.parse(malformed, TEST_ED25519_PUBLIC_KEY), malformed)
        }
    }

    @Test
    fun `agrees with the test parser on what the agent reads`() {
        val fixtures = listOf(
            TEST_ED25519_USER_CERTIFICATE,
            TEST_ED25519_BOUNDED_USER_CERTIFICATE,
            TEST_ED25519_HOST_CERTIFICATE,
            "ssh-ed25519-cert-v01@openssh.com AAAA",
        )
        for (fixture in fixtures) {
            for (publicKey in listOf(TEST_ED25519_PUBLIC_KEY, null)) {
                assertEquals(
                    TestSshCertificateParser.parse(fixture, publicKey)?.readByAgent(),
                    NativeSshCertificateParser.parse(fixture, publicKey)?.readByAgent(),
                    fixture,
                )
            }
        }
    }

    private fun SshCertificate.readByAgent() = listOf(
        type,
        validAfter,
        validBefore,
        publicKeyMatches,
    )
}
