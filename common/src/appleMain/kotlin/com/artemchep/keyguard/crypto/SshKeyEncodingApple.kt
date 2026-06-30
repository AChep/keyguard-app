package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.text.Base64Service

/**
 * Incremental writer for the SSH wire format used by OpenSSH:
 *  - [writeUInt32] writes a 32-bit big-endian integer,
 *  - [writeBlock] / [writeString] write a length-prefixed byte string,
 *  - [writeMpint] writes a multiple-precision integer (length-prefixed,
 *    big-endian, with a leading `0x00` when the high bit is set).
 *
 * This mirrors BouncyCastle's `SSHBuilder` so the bytes produced here are
 * byte-compatible with the JVM key generator.
 */
internal class SshWireBuilder {
    private val out = ArrayList<Byte>()

    fun writeUInt32(value: Int): SshWireBuilder {
        out.add((value ushr 24).toByte())
        out.add((value ushr 16).toByte())
        out.add((value ushr 8).toByte())
        out.add(value.toByte())
        return this
    }

    fun writeRaw(bytes: ByteArray): SshWireBuilder {
        for (b in bytes) {
            out.add(b)
        }
        return this
    }

    fun writeBlock(bytes: ByteArray): SshWireBuilder {
        writeUInt32(bytes.size)
        return writeRaw(bytes)
    }

    fun writeString(value: String): SshWireBuilder =
        writeBlock(value.encodeToByteArray())

    fun writeMpint(magnitude: ByteArray): SshWireBuilder =
        writeBlock(normalizeMpint(magnitude))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Minimal reader for the SSH wire format. Used by the best-effort [parse] path.
 */
internal class SshWireReader(
    private val data: ByteArray,
) {
    private var offset = 0

    fun readUInt32(): Int {
        require(offset + 4 <= data.size) {
            "Unexpected end of SSH data."
        }
        val value = ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
        offset += 4
        return value
    }

    fun readBlock(): ByteArray {
        val length = readUInt32()
        require(length >= 0 && offset + length <= data.size) {
            "Invalid SSH block length."
        }
        return data.copyOfRange(offset, offset + length)
            .also { offset += length }
    }

    fun readString(): String = readBlock().decodeToString()
}

private fun normalizeMpint(
    magnitude: ByteArray,
): ByteArray {
    var start = 0
    while (start < magnitude.size - 1 && magnitude[start].toInt() == 0) {
        start++
    }
    var trimmed = if (start == 0) magnitude else magnitude.copyOfRange(start, magnitude.size)
    if (trimmed.isNotEmpty() && (trimmed[0].toInt() and 0x80) != 0) {
        trimmed = byteArrayOf(0) + trimmed
    }
    return trimmed
}

/**
 * `string("ssh-ed25519") || string(publicKey)` — the OpenSSH public-key wire blob.
 */
internal fun encodeEd25519PublicWire(
    publicKey: ByteArray,
): ByteArray = SshWireBuilder()
    .writeString("ssh-ed25519")
    .writeBlock(publicKey)
    .toByteArray()

/**
 * `string("ssh-rsa") || mpint(e) || mpint(n)` — the OpenSSH public-key wire blob.
 */
internal fun encodeRsaPublicWire(
    modulus: ByteArray,
    exponent: ByteArray,
): ByteArray = SshWireBuilder()
    .writeString("ssh-rsa")
    .writeMpint(exponent)
    .writeMpint(modulus)
    .toByteArray()

/**
 * The unencrypted `openssh-key-v1` private blob (cipher/kdf `none`, single key),
 * matching the layout BouncyCastle's `OpenSSHPrivateKeyUtil` emits for Ed25519.
 */
internal fun encodeEd25519PrivateOpenSsh(
    seed: ByteArray,
    publicKey: ByteArray,
    checkInt: Int,
): ByteArray {
    val authMagic = "openssh-key-v1\u0000".encodeToByteArray()
    val publicWire = encodeEd25519PublicWire(publicKey)

    val privateSection = SshWireBuilder()
        .writeUInt32(checkInt)
        .writeUInt32(checkInt)
        .writeString("ssh-ed25519")
        .writeBlock(publicKey)
        .writeBlock(seed + publicKey)
        .writeString("")
        .toByteArray()
    val padded = padToBlockSize(privateSection, blockSize = 8)

    return SshWireBuilder()
        .writeRaw(authMagic)
        .writeString("none")
        .writeString("none")
        .writeString("")
        .writeUInt32(1)
        .writeBlock(publicWire)
        .writeBlock(padded)
        .toByteArray()
}

private fun padToBlockSize(
    data: ByteArray,
    blockSize: Int,
): ByteArray {
    val align = data.size % blockSize
    if (align == 0) {
        return data
    }
    val padCount = blockSize - align
    val padded = data.copyOf(data.size + padCount)
    for (i in 1..padCount) {
        padded[data.size + i - 1] = i.toByte()
    }
    return padded
}

/**
 * Wraps DER/blob private-key bytes in a PEM block, mirroring the JVM
 * `createPrivateKeyPem` output (headers, line lengths, trailing newline).
 */
internal fun encodePrivateKeyPemApple(
    base64Service: Base64Service,
    type: KeyPair.Type,
    encodedPrivateKey: ByteArray,
): String {
    val header: String
    val lineLength: Int
    when (type) {
        KeyPair.Type.ED25519 -> {
            header = "OPENSSH PRIVATE KEY"
            lineLength = 70
        }

        KeyPair.Type.RSA -> {
            header = "RSA PRIVATE KEY"
            lineLength = 64
        }
    }
    val body = base64Service.encodeToString(encodedPrivateKey)
        .chunked(lineLength)
        .joinToString(separator = "\n")
    return buildString {
        append("-----BEGIN ")
        append(header)
        appendLine("-----")
        appendLine(body)
        append("-----END ")
        append(header)
        appendLine("-----")
    }
}

internal fun bitLengthOfBigEndian(
    bytes: ByteArray,
): Int {
    var i = 0
    while (i < bytes.size && bytes[i].toInt() == 0) {
        i++
    }
    if (i == bytes.size) {
        return 0
    }
    var bits = (bytes.size - i - 1) * 8
    var msb = bytes[i].toInt() and 0xff
    while (msb > 0) {
        bits++
        msb = msb ushr 1
    }
    return bits
}
