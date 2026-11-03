package com.artemchep.keyguard.common.service.crypto

/** OpenSSH finds the certificate of the key `<name>` at `<name>-cert.pub`. */
const val SSH_CERTIFICATE_FILE_SUFFIX = "-cert.pub"

private const val CERTIFICATE_ALGORITHM_SUFFIX = "-cert-v01@openssh.com"

private const val MAX_ALGORITHM_LENGTH = 64

/**
 * The fields of an OpenSSH certificate. The CA signature is not verified: the
 * SSH server decides whether it trusts the certificate.
 */
data class SshCertificate(
    val type: Type,
    val keyId: String,
    val serial: ULong,
    val principals: List<String>,
    /** Unix seconds. */
    val validAfter: Long,
    /** Unix seconds, or [Long.MAX_VALUE] for a certificate that never expires. */
    val validBefore: Long,
    /** The CA key fingerprint as `ssh-keygen -l -E sha256` prints it. */
    val caFingerprint: String,
    val criticalOptions: List<Option>,
    val extensions: List<Option>,
    /** Whether the certificate certifies the public key it was parsed with. */
    val publicKeyMatches: Boolean,
) {
    enum class Type {
        User,
        Host,
    }

    data class Option(
        val name: String,
        /** Empty for flag options, such as the permit-pty extension. */
        val value: String,
    )
}

interface SshCertificateParser {
    /**
     * Reads the [certificate] of the SSH key with the [publicKey]. Returns `null`
     * if the [certificate] is not an OpenSSH certificate.
     */
    fun parse(
        certificate: String,
        publicKey: String?,
    ): SshCertificate?
}

/** Why the certificate of an SSH key is not offered with the key. */
sealed interface SshCertificateProblem {
    /** Not an OpenSSH certificate. */
    data object Malformed : SshCertificateProblem

    /** Only an SSH server presents a host certificate. */
    data object HostCertificate : SshCertificateProblem

    /** The certificate certifies another key. */
    data object KeyMismatch : SshCertificateProblem

    /** Valid from the [validAfter] Unix seconds. */
    data class NotYetValid(val validAfter: Long) : SshCertificateProblem

    /** Expired at the [validBefore] Unix seconds. */
    data class Expired(val validBefore: Long) : SshCertificateProblem
}

/**
 * Whether the [content] starts with an OpenSSH certificate algorithm, as a
 * `-cert.pub` file does. Only the first word is decoded: the rest of a key
 * file may be a private key that must stay in the caller's cleared bytes.
 */
fun isSshCertificateFile(content: ByteArray): Boolean {
    val start = content.indexOfFirst { !it.isAsciiWhitespace() }
    if (start < 0) return false
    var end = start
    while (
        end < content.size &&
        end - start < MAX_ALGORITHM_LENGTH &&
        !content[end].isAsciiWhitespace()
    ) {
        end++
    }
    return content
        .decodeToString(startIndex = start, endIndex = end)
        .endsWith(CERTIFICATE_ALGORITHM_SUFFIX)
}

/** Whether the OpenSSH [line] holds a certificate rather than a plain key. */
fun isSshCertificate(line: String): Boolean = line
    .trimStart()
    .takeWhile { !it.isWhitespace() }
    .endsWith(CERTIFICATE_ALGORITHM_SUFFIX)

private fun Byte.isAsciiWhitespace(): Boolean =
    this == ' '.code.toByte() ||
        this == '\t'.code.toByte() ||
        this == '\n'.code.toByte() ||
        this == '\r'.code.toByte()

/**
 * Returns the problem that keeps the certificate from signing in at
 * [epochSeconds], or `null` if the certificate is usable. The validity
 * window is widened by [clockSkewSeconds] on both ends. Without a public
 * key ([hasPublicKey] is false), the certificate is not checked against one.
 */
fun SshCertificate?.problemAt(
    epochSeconds: Long,
    clockSkewSeconds: Long = 0L,
    hasPublicKey: Boolean = true,
): SshCertificateProblem? = when {
    this == null -> SshCertificateProblem.Malformed
    type != SshCertificate.Type.User -> SshCertificateProblem.HostCertificate
    hasPublicKey && !publicKeyMatches -> SshCertificateProblem.KeyMismatch
    // OpenSSH accepts a certificate from valid_after up to, but not including, valid_before.
    epochSeconds + clockSkewSeconds < validAfter -> SshCertificateProblem.NotYetValid(validAfter)
    epochSeconds - clockSkewSeconds >= validBefore -> SshCertificateProblem.Expired(validBefore)
    else -> null
}
