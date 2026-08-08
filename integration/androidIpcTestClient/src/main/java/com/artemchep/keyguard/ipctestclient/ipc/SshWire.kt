package com.artemchep.keyguard.ipctestclient.ipc

import android.util.Base64
import org.openintents.ssh.authentication.SshAuthenticationApi

private const val LENGTH_PREFIX_BYTES = 4
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val MAX_SSH_FIELD_BYTES = 1024 * 1024

/**
 * One `string` field of an RFC 4253 §6.6 blob: a 32-bit big-endian length
 * followed by that many bytes. Both SSH signatures and SSH public keys are
 * sequences of these.
 */
class SshWireReader(private val bytes: ByteArray) {
    private var offset = 0

    val remaining: Int get() = bytes.size - offset

    fun readField(): ByteArray? {
        if (remaining < LENGTH_PREFIX_BYTES) return null
        var length = 0
        repeat(LENGTH_PREFIX_BYTES) {
            length = (length shl BITS_PER_BYTE) or (bytes[offset++].toInt() and BYTE_MASK)
        }
        return if (length < 0 || length > MAX_SSH_FIELD_BYTES || length > remaining) {
            null
        } else {
            bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }

    fun readString(): String? = readField()?.decodeToString()
}

/** An SSH signature blob: the algorithm name plus the raw signature. */
data class SshSignatureFrame(
    val algorithm: String,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SshSignatureFrame &&
            algorithm == other.algorithm &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + signature.contentHashCode()
}

fun parseSshSignatureFrame(bytes: ByteArray): SshSignatureFrame? {
    val reader = SshWireReader(bytes)
    val algorithm = reader.readString()
    val signature = reader.readField()
    return if (algorithm != null && signature != null && reader.remaining == 0) {
        SshSignatureFrame(algorithm, signature)
    } else {
        null
    }
}

/**
 * Splits a `"<type> <base64> [comment]"` authorized-keys line, checking that the
 * type named up front matches the one embedded in the blob.
 */
fun parseSshPublicKeyLine(line: String): SshSignatureFrame? {
    val parts = line.trim().split(' ').filter(String::isNotEmpty)
    val blob = parts
        .getOrNull(1)
        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
    val embeddedType = blob?.let { SshWireReader(it).readString() }
    return if (blob != null && embeddedType == parts.first()) {
        SshSignatureFrame(parts.first(), blob)
    } else {
        null
    }
}

fun sshKeyAlgorithmName(algorithm: Int): String = when (algorithm) {
    SshAuthenticationApi.RSA -> "RSA"
    SshAuthenticationApi.ECDSA -> "ECDSA"
    SshAuthenticationApi.EDDSA -> "EDDSA"
    SshAuthenticationApi.DSA -> "DSA"
    else -> "UNKNOWN($algorithm)"
}

fun sshHashAlgorithmName(hashAlgorithm: Int): String = when (hashAlgorithm) {
    SshAuthenticationApi.SHA1 -> "SHA1"
    SshAuthenticationApi.SHA224 -> "SHA224"
    SshAuthenticationApi.SHA256 -> "SHA256"
    SshAuthenticationApi.SHA384 -> "SHA384"
    SshAuthenticationApi.SHA512 -> "SHA512"
    SshAuthenticationApi.RIPEMD160 -> "RIPEMD160"
    else -> "UNKNOWN($hashAlgorithm)"
}
