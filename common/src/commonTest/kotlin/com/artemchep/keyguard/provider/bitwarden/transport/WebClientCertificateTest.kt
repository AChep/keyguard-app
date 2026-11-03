package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebClientCertificateTest {
    private val env = ServerEnv(
        baseUrl = "https://vault.example.com/",
        mutualTls = ServerEnv.MutualTls.ImportedPkcs12(pkcs12Base64 = "AAAA"),
    )

    @Test
    fun `the account's servers may get the certificate`() {
        val certificate = env.webClientCertificate()!!

        assertTrue(certificate.permits("vault.example.com", 443))
        assertTrue(certificate.permits("Vault.Example.com", 443))
    }

    @Test
    fun `other servers may not get the certificate`() {
        val certificate = env.webClientCertificate()!!

        assertFalse(certificate.permits("api.duosecurity.com", 443))
        assertFalse(certificate.permits("vault.example.com", 8443))
        assertFalse(certificate.permits("example.com", 443))
    }

    @Test
    fun `accounts without a certificate have none for web pages`() {
        assertNull(env.copy(mutualTls = null).webClientCertificate())
    }

    @Test
    fun `the certificate is not printed`() {
        val certificate = env.webClientCertificate()!!

        assertFalse("AAAA" in certificate.toString())
    }

    @Test
    fun `the stored certificate is not printed`() {
        assertFalse("AAAA" in env.toString())
    }
}
