package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.crypto.SshCertificate
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser

/**
 * Reads the certificates of the test fixtures without the native library.
 * Only the fields the agent reads are filled. `NativeSshCertificateParserTest`
 * checks that the native parser agrees.
 */
internal object TestSshCertificateParser : SshCertificateParser {
    private val certificates = listOf(
        TEST_ED25519_USER_CERTIFICATE to testSshCertificate(
            type = SshCertificate.Type.User,
            validAfter = 0L,
            validBefore = Long.MAX_VALUE,
        ),
        TEST_ED25519_BOUNDED_USER_CERTIFICATE to testSshCertificate(
            type = SshCertificate.Type.User,
            validAfter = TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_AFTER,
            validBefore = TEST_ED25519_BOUNDED_USER_CERTIFICATE_VALID_BEFORE,
        ),
        TEST_ED25519_HOST_CERTIFICATE to testSshCertificate(
            type = SshCertificate.Type.Host,
            validAfter = TEST_ED25519_HOST_CERTIFICATE_VALID_AFTER,
            validBefore = TEST_ED25519_HOST_CERTIFICATE_VALID_BEFORE,
        ),
    )

    override fun parse(
        certificate: String,
        publicKey: String?,
    ): SshCertificate? {
        val (_, value) = certificates
            .firstOrNull { (fixture, _) -> sshPublicKeysMatch(fixture, certificate) }
            ?: return null
        // Every fixture certifies the same key.
        val publicKeyMatches = publicKey != null &&
            sshPublicKeysMatch(publicKey, TEST_ED25519_PUBLIC_KEY)
        return value.copy(publicKeyMatches = publicKeyMatches)
    }
}

internal fun testSshCertificate(
    type: SshCertificate.Type = SshCertificate.Type.User,
    validAfter: Long = 0L,
    validBefore: Long = Long.MAX_VALUE,
    publicKeyMatches: Boolean = true,
) = SshCertificate(
    type = type,
    keyId = "",
    serial = 0uL,
    principals = emptyList(),
    validAfter = validAfter,
    validBefore = validBefore,
    caFingerprint = "",
    criticalOptions = emptyList(),
    extensions = emptyList(),
    publicKeyMatches = publicKeyMatches,
)
