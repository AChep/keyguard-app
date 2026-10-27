package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.crypto.SshCertificate
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.common.service.crypto.problemAt

/**
 * How far the device clock may be off from the server's. The server checks the
 * validity window; the agent only skips a certificate that is clearly outside it.
 */
internal const val SSH_CERTIFICATE_CLOCK_SKEW_SECONDS = 5L * 60L

/**
 * Returns the message of the [certificate] if the agent offers it with the key
 * of this message: a user certificate of that key that is valid at [epochSeconds],
 * give or take [SSH_CERTIFICATE_CLOCK_SKEW_SECONDS].
 */
internal fun SshAgentMessages.SshKey.toCertificateMessage(
    certificate: String?,
    epochSeconds: Long,
    parser: SshCertificateParser,
): SshAgentMessages.SshKey? {
    val text = certificate?.trim()
        ?: return null
    val problem = parser.parse(
        certificate = text,
        publicKey = publicKey,
    ).problemAt(
        epochSeconds = epochSeconds,
        clockSkewSeconds = SSH_CERTIFICATE_CLOCK_SKEW_SECONDS,
    )
    return if (problem == null) {
        copy(
            publicKey = text,
            keyType = extractSshKeyType(text) ?: keyType,
        )
    } else {
        null
    }
}

/**
 * Whether a sign request for the [credential] is for the [certificate] of the
 * key that has the [publicKey]. Like OpenSSH's agent, this does not check the
 * validity window: the server does.
 */
internal fun sshCertificateMatches(
    certificate: String?,
    publicKey: String?,
    credential: String,
    parser: SshCertificateParser,
): Boolean {
    val text = certificate
        ?.takeIf { sshPublicKeysMatch(it, credential) }
        ?: return false
    val description = parser.parse(
        certificate = text,
        publicKey = publicKey,
    )
    return description != null &&
        description.type == SshCertificate.Type.User &&
        description.publicKeyMatches
}
