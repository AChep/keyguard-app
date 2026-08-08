@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public enum class NativeSshKeyType {
    RSA,
    ED25519,
}

public class NativeSshKeyMaterial(
    val type: NativeSshKeyType,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

public class NativeSshKeyCxfExport(
    val type: NativeSshKeyType,
    val privateKeyPkcs8: ByteArray,
)

public data class NativeSshKeyDescription(
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val privateFingerprint: String,
    val publicFingerprint: String,
) {
    /** Keeps the private key out of logs and diagnostics. */
    override fun toString(): String = "NativeSshKeyDescription(" +
        "privateKeyPem=<redacted>, " +
        "publicKeyOpenSsh=$publicKeyOpenSsh, " +
        "privateFingerprint=$privateFingerprint, " +
        "publicFingerprint=$publicFingerprint" +
        ")"
}

public class NativeSshSignature(
    val algorithm: String,
    val signature: ByteArray,
)

public class NativeSshPublicKey(
    val type: NativeSshKeyType,
    /** Canonical SSH algorithm name, e.g. "ssh-rsa" or "ssh-ed25519". */
    val algorithmName: String,
    /** X.509 SubjectPublicKeyInfo DER encoding of the public key. */
    val spkiDer: ByteArray,
)

public sealed interface NativeSshPrivateKeyImportResult {
    public class Success(
        val keyMaterial: NativeSshKeyMaterial,
    ) : NativeSshPrivateKeyImportResult

    public data class NeedsPassphrase(
        val formatLabel: String,
    ) : NativeSshPrivateKeyImportResult

    public data class Error(
        val reason: NativeSshPrivateKeyImportError,
    ) : NativeSshPrivateKeyImportResult
}

public enum class NativeSshPrivateKeyImportError {
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_ALGORITHM,
    INVALID_PASSPHRASE,
    MALFORMED_KEY,
}

public object NativeCryptoSsh {
    private const val MAX_KEY_BYTES: Int = 64 * 1024
    private const val MAX_IMPORT_DOCUMENT_BYTES: Int = 1024 * 1024
    private const val MAX_PASSPHRASE_BYTES: Int = 16 * 1024
    private const val MAX_SIGN_DATA_BYTES: Int = 1024 * 1024
    private const val MIN_RSA_SIGNATURE_BYTES: Int = 128
    private const val MAX_RSA_SIGNATURE_BYTES: Int = 1024
    private const val RSA_1024_KEY_BITS: Int = 1024
    private const val RSA_2048_KEY_BITS: Int = 2048
    private const val RSA_3072_KEY_BITS: Int = 3072
    private const val RSA_4096_KEY_BITS: Int = 4096
    private const val ED25519_SIGNATURE_BYTES: Int = 64
    private const val SSH_STRING_LENGTH_BYTES: Int = Int.SIZE_BYTES
    private const val BITS_PER_BYTE: Int = 8
    private const val BYTE_MASK: Int = 0xff

    /**
     * Canonical SSH algorithm names shared by every consumer of the native
     * SSH implementation. These are the only values [sign] emits; they mirror
     * `keyguard-crypto-core/src/ssh_keys.rs`.
     */
    public const val ALGORITHM_SSH_ED25519: String = "ssh-ed25519"
    public const val ALGORITHM_SSH_RSA: String = "ssh-rsa"
    public const val ALGORITHM_RSA_SHA2_256: String = "rsa-sha2-256"
    public const val ALGORITHM_RSA_SHA2_512: String = "rsa-sha2-512"

    /**
     * ssh-agent `SSH_AGENT_RSA_SHA2_*` signature flags consumed by [sign];
     * they mirror the flag precedence in `ssh_keys.rs` `sign_rsa`: 0x04
     * selects rsa-sha2-512, else 0x02 selects rsa-sha2-256, else ssh-rsa.
     */
    public const val AGENT_FLAG_RSA_SHA2_256: Int = 0x02
    public const val AGENT_FLAG_RSA_SHA2_512: Int = 0x04

    /**
     * RSA modulus sizes accepted by the native SSH key generator. This is the
     * source of truth for user-facing key size options; anything offered in the
     * UI must be present in this set.
     */
    public val SUPPORTED_RSA_KEY_BITS: Set<Int> = setOf(
        RSA_1024_KEY_BITS,
        RSA_2048_KEY_BITS,
        RSA_3072_KEY_BITS,
        RSA_4096_KEY_BITS,
    )

    public fun generate(
        type: NativeSshKeyType,
        rsaBits: Int? = null,
    ): NativeSshKeyMaterial {
        when (type) {
            NativeSshKeyType.RSA -> require(rsaBits in SUPPORTED_RSA_KEY_BITS) {
                "RSA key size must be 1024, 2048, 3072, or 4096 bits"
            }

            NativeSshKeyType.ED25519 -> require(rsaBits == null) {
                "Ed25519 generation does not accept an RSA key size"
            }
        }
        return decodeMaterial(
            operation = "ssh_key_generate",
            payload = NativeCrypto.call(
                operationName = "ssh_key_generate",
                operation = SshKeyGenerateOperationProto(
                    SshKeyGenerateRequestProto(type.toProto(), rsaBits ?: 0),
                ),
            ).requireBytes("ssh_key_generate"),
        )
    }

    public fun parse(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): NativeSshKeyMaterial {
        requireEncodedKeyBounds(privateKeyPem, publicKeyOpenSsh)
        return decodeMaterial(
            operation = "ssh_key_parse",
            payload = NativeCrypto.call(
                operationName = "ssh_key_parse",
                operation = SshKeyParseOperationProto(
                    SshKeyParseRequestProto(privateKeyPem, publicKeyOpenSsh),
                ),
            ).requireBytes("ssh_key_parse"),
        )
    }

    public fun describe(
        type: NativeSshKeyType,
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): NativeSshKeyDescription {
        requireRawKeyBounds(privateKey, publicKey)
        val payload = NativeCrypto.call(
            operationName = "ssh_key_describe",
            operation = SshKeyDescribeOperationProto(
                SshKeyDescribeRequestProto(type.toProto(), privateKey, publicKey),
            ),
        ).requireBytes("ssh_key_describe")
        return decodePayload<SshKeyDescriptionProto>("ssh_key_describe", payload).let { value ->
            if (
                value.privateKeyPem.isEmpty() ||
                value.publicKeyOpenSsh.isEmpty() ||
                value.privateFingerprint.isEmpty() ||
                value.publicFingerprint.isEmpty()
            ) {
                malformed("ssh_key_describe")
            }
            NativeSshKeyDescription(
                privateKeyPem = value.privateKeyPem,
                publicKeyOpenSsh = value.publicKeyOpenSsh,
                privateFingerprint = value.privateFingerprint,
                publicFingerprint = value.publicFingerprint,
            )
        }
    }

    public fun privateKeyRsaBits(privateKey: ByteArray): Int? {
        require(privateKey.size <= MAX_KEY_BYTES) { "SSH private key is too large" }
        val bits = NativeCrypto.callInt32(
            operationName = "ssh_private_key_rsa_bits",
            operation = SshPrivateKeyRsaBitsOperationProto(
                SshPrivateKeyRsaBitsRequestProto(privateKey),
            ),
        )
        if (bits < 0) malformed("ssh_private_key_rsa_bits")
        return bits.takeIf { it > 0 }
    }

    public fun formatPrivateKey(
        type: NativeSshKeyType,
        privateKey: ByteArray,
    ): String {
        require(privateKey.isNotEmpty()) { "SSH private key must not be empty" }
        require(privateKey.size <= MAX_KEY_BYTES) { "SSH private key is too large" }
        val payload = NativeCrypto.call(
            operationName = "ssh_private_key_format",
            operation = SshPrivateKeyFormatOperationProto(
                SshPrivateKeyFormatRequestProto(type.toProto(), privateKey),
            ),
        ).requireBytes("ssh_private_key_format")
        return decodePayload<SshFormattedPrivateKeyProto>("ssh_private_key_format", payload)
            .value
            .takeIf(String::isNotEmpty)
            ?: malformed("ssh_private_key_format")
    }

    /**
     * Validates a stored SSH public/private key pair and emits the private key
     * as the PKCS#8 DER required by Credential Exchange Format. Legacy RSA
     * records with missing CRT values are completed inside the native sensitive
     * backend before export. Ed25519 is normalized to the broadly interoperable
     * RFC 8410 v1 form; RSA uses an explicit NULL rsaEncryption parameter.
     */
    public fun exportCxf(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): NativeSshKeyCxfExport {
        requireEncodedKeyBounds(privateKeyPem, publicKeyOpenSsh)
        val payload = NativeCrypto.call(
            operationName = "ssh_key_export_cxf",
            operation = SshKeyExportCxfOperationProto(
                SshKeyExportCxfRequestProto(
                    privateKeyPem = privateKeyPem,
                    publicKeyOpenSsh = publicKeyOpenSsh,
                ),
            ),
        ).requireBytes("ssh_key_export_cxf")
        val value = decodePayload<SshKeyExportCxfResultProto>(
            operation = "ssh_key_export_cxf",
            payload = payload,
        )
        return try {
            val type = value.type.toPublic("ssh_key_export_cxf")
            if (
                value.privateKeyPkcs8.isEmpty() ||
                value.privateKeyPkcs8.size > MAX_KEY_BYTES
            ) {
                malformed("ssh_key_export_cxf")
            }
            NativeSshKeyCxfExport(
                type = type,
                privateKeyPkcs8 = value.privateKeyPkcs8,
            )
        } catch (failure: Throwable) {
            value.privateKeyPkcs8.fill(0)
            throw failure
        }
    }

    public fun sign(
        privateKeyPem: String,
        publicKeyOpenSsh: String?,
        data: ByteArray,
        flags: Int,
    ): NativeSshSignature {
        require(privateKeyPem.isNotEmpty()) { "SSH private key must not be empty" }
        require(privateKeyPem.encodeToByteArray().size <= MAX_KEY_BYTES) {
            "SSH private key is too large"
        }
        publicKeyOpenSsh?.let { publicKey ->
            require(publicKey.encodeToByteArray().size <= MAX_KEY_BYTES) {
                "SSH public key is too large"
            }
        }
        require(data.size <= MAX_SIGN_DATA_BYTES) { "SSH signing input is too large" }
        val payload = NativeCrypto.call(
            operationName = "ssh_agent_sign",
            operation = SshAgentSignOperationProto(
                SshAgentSignRequestProto(privateKeyPem, publicKeyOpenSsh, data, flags),
            ),
        ).requireBytes("ssh_agent_sign")
        val value = decodePayload<SshSignatureProto>("ssh_agent_sign", payload)
        val validSignatureSize = when (value.algorithm) {
            ALGORITHM_SSH_ED25519 -> value.signature.size == ED25519_SIGNATURE_BYTES
            ALGORITHM_SSH_RSA, ALGORITHM_RSA_SHA2_256, ALGORITHM_RSA_SHA2_512 ->
                value.signature.size in MIN_RSA_SIGNATURE_BYTES..MAX_RSA_SIGNATURE_BYTES

            else -> false
        }
        if (!validSignatureSize) {
            value.signature.fill(0)
            malformed("ssh_agent_sign")
        }
        return NativeSshSignature(value.algorithm, value.signature)
    }

    /**
     * Decodes a stored OpenSSH public key line ("<type> <base64> [comment]")
     * into its X.509 SubjectPublicKeyInfo DER form. The native `ssh-key`
     * parser is the single validator: it binds the declared type prefix to
     * the embedded key blob and rejects malformed or trailing wire data.
     */
    public fun decodePublicKey(publicKeyOpenSsh: String): NativeSshPublicKey {
        require(publicKeyOpenSsh.isNotEmpty()) { "SSH public key must not be empty" }
        require(publicKeyOpenSsh.encodeToByteArray().size <= MAX_KEY_BYTES) {
            "SSH public key is too large"
        }
        val payload = NativeCrypto.call(
            operationName = "ssh_public_key_decode",
            operation = SshPublicKeyDecodeOperationProto(
                SshPublicKeyDecodeRequestProto(publicKeyOpenSsh),
            ),
        ).requireBytes("ssh_public_key_decode")
        val value = decodePayload<SshPublicKeyDecodeResultProto>(
            operation = "ssh_public_key_decode",
            payload = payload,
        )
        val type = value.type.toPublic("ssh_public_key_decode")
        if (value.spkiDer.isEmpty() || value.spkiDer.size > MAX_KEY_BYTES) {
            malformed("ssh_public_key_decode")
        }
        return NativeSshPublicKey(
            type = type,
            algorithmName = when (type) {
                NativeSshKeyType.RSA -> ALGORITHM_SSH_RSA
                NativeSshKeyType.ED25519 -> ALGORITHM_SSH_ED25519
            },
            spkiDer = value.spkiDer,
        )
    }

    /**
     * Frames a signature as the RFC 4253 section 6.6 blob consumed by SSH
     * clients: string(algorithm) followed by string(signature). Stays
     * byte-compatible with the framing the native agent front-ends apply at
     * their own wire boundaries (androidSshAgent `encode_sign_response`).
     */
    public fun frameSignature(signature: NativeSshSignature): ByteArray {
        val algorithm = signature.algorithm.encodeToByteArray()
        val output = ByteArray(
            2 * SSH_STRING_LENGTH_BYTES + algorithm.size + signature.signature.size,
        )
        val offset = writeSshString(output, 0, algorithm)
        writeSshString(output, offset, signature.signature)
        return output
    }

    /** Writes a big-endian length-prefixed SSH string, returns the new offset. */
    private fun writeSshString(output: ByteArray, offset: Int, value: ByteArray): Int {
        var cursor = offset
        repeat(SSH_STRING_LENGTH_BYTES) { index ->
            val shift = (SSH_STRING_LENGTH_BYTES - 1 - index) * BITS_PER_BYTE
            output[cursor] = ((value.size ushr shift) and BYTE_MASK).toByte()
            cursor++
        }
        value.copyInto(output, destinationOffset = cursor)
        return cursor + value.size
    }

    public fun importPrivateKey(
        content: String,
        passphrase: String? = null,
    ): NativeSshPrivateKeyImportResult {
        // A UTF-8 encoding is never shorter than the source UTF-16 code-unit
        // count. Reject clearly oversized inputs before allocating their byte
        // representation, then enforce the exact encoded-byte limits below.
        if (
            content.length > MAX_IMPORT_DOCUMENT_BYTES ||
            passphrase != null && passphrase.length > MAX_PASSPHRASE_BYTES
        ) {
            importResourceLimit()
        }
        val contentUtf8 = content.encodeToByteArray()
        val passphraseUtf8 = passphrase?.encodeToByteArray()
        try {
            if (
                contentUtf8.size > MAX_IMPORT_DOCUMENT_BYTES ||
                passphraseUtf8 != null && passphraseUtf8.size > MAX_PASSPHRASE_BYTES
            ) {
                importResourceLimit()
            }
            val payload = NativeCrypto.call(
                operationName = "ssh_private_key_import",
                operation = SshPrivateKeyImportOperationProto(
                    SshPrivateKeyImportRequestProto(
                        content = content,
                        passphraseUtf8 = passphraseUtf8,
                    ),
                ),
            ).requireBytes("ssh_private_key_import")
            val result = decodePayload<SshPrivateKeyImportResultProto>(
                operation = "ssh_private_key_import",
                payload = payload,
            )
            return when (val outcome = result.result) {
                is SshPrivateKeyImportSuccessOutcomeProto -> {
                    val material = outcome.value.keyMaterial
                        ?: malformed("ssh_private_key_import")
                    NativeSshPrivateKeyImportResult.Success(
                        decodeMaterial("ssh_private_key_import", material),
                    )
                }

                is SshPrivateKeyImportNeedsPassphraseOutcomeProto -> {
                    val label = outcome.value.formatLabel
                        .takeIf(String::isNotEmpty)
                        ?: malformed("ssh_private_key_import")
                    NativeSshPrivateKeyImportResult.NeedsPassphrase(label)
                }

                is SshPrivateKeyImportErrorOutcomeProto -> {
                    val reason = when (outcome.value.reason) {
                        SshPrivateKeyImportErrorReasonProto.UNSUPPORTED_FORMAT ->
                            NativeSshPrivateKeyImportError.UNSUPPORTED_FORMAT

                        SshPrivateKeyImportErrorReasonProto.UNSUPPORTED_ALGORITHM ->
                            NativeSshPrivateKeyImportError.UNSUPPORTED_ALGORITHM

                        SshPrivateKeyImportErrorReasonProto.INVALID_PASSPHRASE ->
                            NativeSshPrivateKeyImportError.INVALID_PASSPHRASE

                        SshPrivateKeyImportErrorReasonProto.MALFORMED_KEY ->
                            NativeSshPrivateKeyImportError.MALFORMED_KEY

                        SshPrivateKeyImportErrorReasonProto.UNSPECIFIED ->
                            malformed("ssh_private_key_import")
                    }
                    NativeSshPrivateKeyImportResult.Error(reason)
                }

                null -> malformed("ssh_private_key_import")
            }
        } finally {
            contentUtf8.fill(0)
            passphraseUtf8?.fill(0)
        }
    }

    private fun importResourceLimit(): Nothing = throw NativeCryptoException(
        operation = "ssh_private_key_import",
        code = NativeCryptoErrorCode.RESOURCE_LIMIT,
    )

    private fun decodeMaterial(operation: String, payload: ByteArray): NativeSshKeyMaterial {
        val value = decodePayload<SshKeyMaterialProto>(operation, payload)
        return decodeMaterial(operation, value)
    }

    private fun decodeMaterial(
        operation: String,
        value: SshKeyMaterialProto,
    ): NativeSshKeyMaterial {
        return try {
            val type = value.type.toPublic(operation)
            if (
                value.privateKey.isEmpty() || value.privateKey.size > MAX_KEY_BYTES ||
                value.publicKey.isEmpty() || value.publicKey.size > MAX_KEY_BYTES
            ) {
                malformed(operation)
            }
            NativeSshKeyMaterial(type, value.privateKey, value.publicKey)
        } catch (failure: Throwable) {
            value.privateKey.fill(0)
            value.publicKey.fill(0)
            throw failure
        }
    }

    private inline fun <reified T> decodePayload(operation: String, payload: ByteArray): T = try {
        ProtoBuf.decodeFromByteArray<T>(payload)
    } catch (_: SerializationException) {
        malformed(operation)
    } finally {
        payload.fill(0)
    }

    private fun requireEncodedKeyBounds(privateKeyPem: String, publicKeyOpenSsh: String) {
        require(privateKeyPem.isNotEmpty()) { "SSH private key must not be empty" }
        require(publicKeyOpenSsh.isNotEmpty()) { "SSH public key must not be empty" }
        require(privateKeyPem.encodeToByteArray().size <= MAX_KEY_BYTES) { "SSH private key is too large" }
        require(publicKeyOpenSsh.encodeToByteArray().size <= MAX_KEY_BYTES) { "SSH public key is too large" }
    }

    private fun requireRawKeyBounds(privateKey: ByteArray, publicKey: ByteArray) {
        require(privateKey.isNotEmpty()) { "SSH private key must not be empty" }
        require(publicKey.isNotEmpty()) { "SSH public key must not be empty" }
        require(privateKey.size <= MAX_KEY_BYTES) { "SSH private key is too large" }
        require(publicKey.size <= MAX_KEY_BYTES) { "SSH public key is too large" }
    }
}

private fun NativeSshKeyType.toProto(): SshKeyTypeProto = when (this) {
    NativeSshKeyType.RSA -> SshKeyTypeProto.RSA
    NativeSshKeyType.ED25519 -> SshKeyTypeProto.ED25519
}

private fun SshKeyTypeProto.toPublic(operation: String): NativeSshKeyType = when (this) {
    SshKeyTypeProto.RSA -> NativeSshKeyType.RSA
    SshKeyTypeProto.ED25519 -> NativeSshKeyType.ED25519
    SshKeyTypeProto.UNSPECIFIED -> malformed(operation)
}

private fun malformed(operation: String): Nothing = throw NativeCryptoException(
    operation = operation,
    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
)
