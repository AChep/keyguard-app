package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.service.crypto.SshCertificate
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.common.service.sshagent.testSshCertificate
import com.artemchep.keyguard.test.TEST_INSTANT
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchtowerSshCertificateUnusableTest {
    private val client = WatchtowerSshCertificateUnusable(
        sshCertificateParser = FakeParser(
            "valid" to testSshCertificate(),
            "expired" to testSshCertificate(validBefore = 1L),
            "not-yet-valid" to testSshCertificate(validAfter = Long.MAX_VALUE),
            "host" to testSshCertificate(type = SshCertificate.Type.Host),
            "other-key" to testSshCertificate(publicKeyMatches = false),
        ),
    )

    @Test
    fun `flags each reason the agent does not offer a certificate`() = runTest {
        val results = client.process(
            listOf(
                sshKey("valid"),
                sshKey("expired"),
                sshKey("not-yet-valid"),
                sshKey("host"),
                sshKey("other-key"),
                sshKey("malformed"),
            ),
        )

        assertEquals(
            listOf(
                "valid" to null,
                "expired" to "expired",
                "not-yet-valid" to "not_yet_valid",
                "host" to "host_certificate",
                "other-key" to "key_mismatch",
                "malformed" to "malformed",
            ),
            results.map { it.cipher.id to it.value },
        )
        results.forEach { assertEquals(it.value != null, it.threat, it.cipher.id) }
    }

    @Test
    fun `skips keys without a certificate`() = runTest {
        val results = client.process(
            listOf(
                sshKey("none", certificate = null),
                sshKey("blank", certificate = " "),
                createSecret(id = "login"),
            ),
        )

        assertEquals(listOf(false, false, false), results.map { it.threat })
    }

    @Test
    fun `a certificate is not checked against a missing key`() = runTest {
        // The key may be added after its certificate, as the editor allows.
        val result = client.process(listOf(sshKey("other-key", publicKey = null))).single()

        assertEquals(false, result.threat)
    }

    @Test
    fun `ignoring the alert clears it`() = runTest {
        val cipher = sshKey("expired").copy(
            ignoredAlerts = mapOf(DWatchtowerAlertType.SSH_CERTIFICATE_UNUSABLE to TEST_INSTANT),
        )

        val result = client.process(listOf(cipher)).single()

        assertEquals(false, result.threat)
        assertEquals(null, result.value)
    }

    private fun sshKey(
        id: String,
        certificate: String? = id,
        publicKey: String? = "ssh-ed25519 key",
    ) = createSecret(
        id = id,
        type = DSecret.Type.SshKey,
        sshKey = DSecret.SshKey(
            publicKey = publicKey,
            certificate = certificate,
        ),
    )

    private class FakeParser(
        vararg certificates: Pair<String, SshCertificate>,
    ) : SshCertificateParser {
        private val certificates = certificates.toMap()

        override fun parse(
            certificate: String,
            publicKey: String?,
        ): SshCertificate? = certificates[certificate]
            ?.let { it.copy(publicKeyMatches = it.publicKeyMatches && publicKey != null) }
    }
}
