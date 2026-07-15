@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public enum class NativeSshKeyType {
    RSA,
    ED25519,
}

public data class NativeSshKeyMaterial(
    val type: NativeSshKeyType,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
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

public data class NativeSshSignature(
    val algorithm: String,
    val signature: ByteArray,
)

public sealed interface NativeSshPrivateKeyImportResult {
    public data class Success(
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

    public fun generate(
        type: NativeSshKeyType,
        rsaBits: Int? = null,
    ): NativeSshKeyMaterial {
        when (type) {
            NativeSshKeyType.RSA -> require(rsaBits in setOf(1024, 2048, 3072, 4096)) {
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
            "ssh-ed25519" -> value.signature.size == 64
            "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" ->
                value.signature.size in MIN_RSA_SIGNATURE_BYTES..MAX_RSA_SIGNATURE_BYTES

            else -> false
        }
        if (!validSignatureSize) {
            value.signature.fill(0)
            malformed("ssh_agent_sign")
        }
        return NativeSshSignature(value.algorithm, value.signature)
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
