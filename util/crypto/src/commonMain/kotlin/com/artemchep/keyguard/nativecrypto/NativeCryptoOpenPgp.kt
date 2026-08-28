@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public sealed interface NativeOpenPgpPublicKeyParseResult {
    public data class Success(
        val keys: List<NativeOpenPgpPublicKeyInfo>,
        /**
         * Number of independent certificates omitted because their version is not
         * supported, they are malformed, or their policy evaluation exceeds the
         * per-certificate work budget. Such certificates are tolerated rather than
         * fatal when another recoverable certificate succeeds.
         */
        val skippedCertificates: Int = 0,
    ) : NativeOpenPgpPublicKeyParseResult

    public data class Error(
        val reason: NativeOpenPgpPublicKeyParseError,
    ) : NativeOpenPgpPublicKeyParseResult
}

public enum class NativeOpenPgpPublicKeyParseError {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_KEY_VERSION,
    MULTIPLE_CERTIFICATES,
}

public data class NativeOpenPgpPublicKeyInfo(
    val fingerprint: String,
    val keygrip: String?,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    val userIds: List<String>,
    val emails: List<String>,
    val createdAtEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    val revoked: Boolean,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val publicKeyArmored: String,
    val subkeys: List<NativeOpenPgpPublicSubKeyInfo>,
    /**
     * Whether a policy-acceptable self-signature authenticates this key.
     *
     * A reported key with `authenticated == false` is bound only by a
     * signature below the hash policy: it authorizes nothing, but renewal can
     * still reissue that signature with a modern algorithm.
     */
    val authenticated: Boolean = true,
    /**
     * Whether recertification may reissue this key's own self-signatures.
     *
     * This is what tells the two `authenticated == false` keys apart:
     * [NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY] is the weak-hash key a
     * renewal repairs, [NativeOpenPgpRenewalAuthorization.NONE] is the key a
     * renewal cannot touch. Subkeys carry no such field: an unauthenticated
     * subkey is only reported when it is template-renewable.
     */
    val renewal: NativeOpenPgpRenewalAuthorization =
        NativeOpenPgpRenewalAuthorization.NONE,
)

public data class NativeOpenPgpPublicSubKeyInfo(
    val fingerprint: String,
    val keygrip: String?,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val revoked: Boolean,
    val createdAtEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    /** See [NativeOpenPgpPublicKeyInfo.authenticated]. */
    val authenticated: Boolean = true,
)

/** Payload signature result; signing-key policy is reported separately in `warnings`. */
public enum class NativeOpenPgpVerificationStatus {
    VALID,
    INVALID,
    MISSING_PUBLIC_KEY,
}

public enum class NativeOpenPgpVerificationWarning {
    /** The signature may verify mathematically, but the signing authority is revoked. */
    KEY_REVOKED,

    /** The signature may verify mathematically, but the signing authority is expired. */
    KEY_EXPIRED,

    /** The signature statement has expired and is therefore reported as invalid. */
    SIGNATURE_EXPIRED,

    /**
     * Equally recent authenticated policy signatures disagree about the effective key policy.
     * The payload signature may still be cryptographically valid, but signer identity and
     * capabilities cannot be uniquely authenticated.
     */
    POLICY_CONFLICT,

    /**
     * The data signature is bound to a digest algorithm that is no longer considered
     * collision resistant (SHA-1 or MD5). Such signatures are never reported as valid.
     */
    WEAK_DIGEST,
}

public data class NativeOpenPgpVerification(
    val status: NativeOpenPgpVerificationStatus,
    val keyId: String,
    val fingerprint: String?,
    val userIds: List<String>,
    val createdAtEpochSeconds: Long?,
    val warnings: List<NativeOpenPgpVerificationWarning>,
    /** One leaf result per input signature, in packet order. */
    val signatures: List<NativeOpenPgpVerification> = emptyList(),
) {
    /** True only for an unqualified valid result under Keyguard's caller policy. */
    public val isPolicyAccepted: Boolean
        get() = status == NativeOpenPgpVerificationStatus.VALID && warnings.isEmpty()
}

public data class NativeOpenPgpClearVerifyResult(
    val verification: NativeOpenPgpVerification,
    /** True when every recovered body line is valid UTF-8. */
    val bodyValidUtf8: Boolean,
)

public data class NativeOpenPgpMetadataResolution(
    val certificates: List<NativeOpenPgpCertificateResolution>,
    val evaluatedAtEpochSeconds: Long,
    val policyRevision: Int,
)

public enum class NativeOpenPgpKeyComponentRole {
    PRIMARY,
    SUBKEY,
}

public enum class NativeOpenPgpAgentOperation {
    SIGN,
    DECRYPT,
}

public enum class NativeOpenPgpPolicyUse {
    SIGN_NEW_DATA,
    ENCRYPT_NEW_DATA,
}

/** Effective revocation state at the metadata evaluation time. */
public enum class NativeOpenPgpRevocationStatus {
    NOT_REVOKED,
    REVOKED,
    INDETERMINATE,
}

/**
 * Whether recertification may reissue a component's own self-signatures.
 *
 * [TEMPLATE_ONLY] is the legacy rescue tier: the component authenticates
 * nothing, yet renewal stays available because the renewal is exactly what
 * replaces its weak-hash self-signatures with modern ones. It authorizes no
 * other operation. [NONE] covers every refusal, including revoked components;
 * revocation is reported through its own fields.
 */
public enum class NativeOpenPgpRenewalAuthorization {
    AUTHENTICATED,
    TEMPLATE_ONLY,
    NONE,
}

public data class NativeOpenPgpKeyComponentIndex(
    val fingerprint: String,
    val role: NativeOpenPgpKeyComponentRole,
    val publicKeyAlgorithmId: Int,
    val algorithm: String,
    val keygrips: List<String>,
    val storedSecretMaterial: Boolean,
    val agentOperations: Set<NativeOpenPgpAgentOperation>,
)

public data class NativeOpenPgpLegacyDesignatedRevoker(
    val publicKeyAlgorithmId: Int,
    val fingerprint: String,
    val keyClass: Int,
    val sensitive: Boolean,
)

public data class NativeOpenPgpCertificateIndex(
    val primaryFingerprint: String,
    val components: List<NativeOpenPgpKeyComponentIndex>,
    val legacyDesignatedRevokers: List<NativeOpenPgpLegacyDesignatedRevoker>,
)

public data class NativeOpenPgpComponentPolicy(
    val fingerprint: String,
    val allowedNewDataUses: Set<NativeOpenPgpPolicyUse>,
    val renewal: NativeOpenPgpRenewalAuthorization =
        NativeOpenPgpRenewalAuthorization.NONE,
    val revocationStatus: NativeOpenPgpRevocationStatus =
        NativeOpenPgpRevocationStatus.INDETERMINATE,
)

public data class NativeOpenPgpCertificateResolution(
    val index: NativeOpenPgpCertificateIndex,
    val policy: List<NativeOpenPgpComponentPolicy>,
)

public enum class NativeOpenPgpKeyKind {
    LEGACY_ED25519_X25519,
    RSA,
}

public class NativeOpenPgpKeyMaterial(
    val privateKeyArmored: ByteArray,
    val publicKeyArmored: ByteArray,
    val fingerprint: String,
)

public sealed interface NativeOpenPgpKeyImportResult {
    public class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
    ) : NativeOpenPgpKeyImportResult

    public data class NeedsPassphrase(
        val formatLabel: String,
    ) : NativeOpenPgpKeyImportResult

    public data class Error(
        val reason: NativeOpenPgpKeyImportError,
    ) : NativeOpenPgpKeyImportResult
}

public enum class NativeOpenPgpKeyImportError {
    EMPTY,
    UNSUPPORTED_FORMAT,
    INVALID_PASSPHRASE,
    MALFORMED_KEY,
}

public enum class NativeOpenPgpProtectionMode {
    SEIPD_V1_MDC,
    GNUPG_OCB,
    SEIPD_V2_AEAD,
}

public class NativeOpenPgpEncryptResult(
    val data: ByteArray,
    val protectionMode: NativeOpenPgpProtectionMode,
)

public enum class NativeOpenPgpDecryptionWarning {
    /** RFC 9580 Section 12.4: successful historical decryption used RSA below 3072 bits. */
    WEAK_RSA_KEY,

    /** RFC 9580 Section 12.6: successful historical decryption used deprecated ElGamal. */
    ELGAMAL_KEY,
}

public class NativeOpenPgpDecryptResult(
    val data: ByteArray,
    val verification: NativeOpenPgpVerification?,
    /** See [NativeOpenPgpLiteralMetadata] for its authenticity limits. */
    val metadata: NativeOpenPgpLiteralMetadata?,
    /** True when the input was encrypted rather than accepted signed-only. */
    val encrypted: Boolean,
    /**
     * Raw value of the message "Charset:" armor header, when exactly one is
     * present. Armor headers sit outside every integrity envelope, so this
     * is an unauthenticated transport hint that anyone in the path can add
     * or alter; prefer inspecting [data] itself over trusting it.
     */
    val declaredCharset: String?,
    /** Exact primary key or subkey component that recovered the session key. */
    val decryptionKeyFingerprint: String? = null,
    /** Deprecation warnings for the component that recovered the session key. */
    val warnings: List<NativeOpenPgpDecryptionWarning> = emptyList(),
)

public class NativeOpenPgpEncryptFinal(
    val data: ByteArray,
    val protectionMode: NativeOpenPgpProtectionMode,
)

public class NativeOpenPgpDecryptFinal(
    val data: ByteArray,
    val verification: NativeOpenPgpVerification?,
    /** See [NativeOpenPgpLiteralMetadata] for its authenticity limits. */
    val metadata: NativeOpenPgpLiteralMetadata?,
    /** True when the input was encrypted rather than accepted signed-only. */
    val encrypted: Boolean,
    /**
     * Raw value of the message "Charset:" armor header, when exactly one is
     * present. Armor headers sit outside every integrity envelope, so this
     * is an unauthenticated transport hint that anyone in the path can add
     * or alter; prefer inspecting [data] itself over trusting it.
     */
    val declaredCharset: String?,
    /** Exact primary key or subkey component that recovered the session key. */
    val decryptionKeyFingerprint: String? = null,
    /** Deprecation warnings for the component that recovered the session key. */
    val warnings: List<NativeOpenPgpDecryptionWarning> = emptyList(),
)

/**
 * Header of the OpenPGP literal data packet. OpenPGP signatures cover only
 * the literal *data*, never this header — so when [NativeOpenPgpDecryptResult.encrypted]
 * is false, every field except [originalSize] is attacker-malleable even
 * while the signature verifies as valid. For encrypted messages the header
 * is integrity-protected by the encryption envelope, but it is still
 * whatever the sender chose to claim.
 */
public class NativeOpenPgpLiteralMetadata(
    /**
     * Raw file name bytes embedded by the sender: unauthenticated and
     * unvalidated. Unsafe to use as a filesystem path (may contain path
     * separators or traversal) and unsafe to render unescaped (may contain
     * control characters; see GnuPG CVE-2018-12020 "SigSpoof").
     */
    val fileName: ByteArray,
    val format: Int,
    val modificationTimeEpochSeconds: Long,
    /**
     * Actual decoded plaintext size in bytes, counted by the native layer —
     * not the sender-declared length.
     */
    val originalSize: Long,
)

public sealed interface NativeOpenPgpExpirationUpdateResult {
    public class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
        val certificateIndex: NativeOpenPgpCertificateIndex,
    ) : NativeOpenPgpExpirationUpdateResult

    public data class Error(
        val reason: NativeOpenPgpExpirationUpdateError,
    ) : NativeOpenPgpExpirationUpdateResult
}

public enum class NativeOpenPgpExpirationUpdateError {
    EMPTY_PRIVATE_KEY,
    MALFORMED_KEY,
    FINGERPRINT_MISMATCH,
    NO_COMPONENTS_SELECTED,
    COMPONENT_NOT_FOUND,
    REVOKED_COMPONENT,
    UNRESOLVED_REVOCATION_AUTHORITY,
    UNSUPPORTED_KEY_VERSION,
    MISSING_SECRET_KEY,
    PROTECTED_SECRET_KEY,
    MISSING_SELF_SIGNATURE,
    INVALID_EXPIRATION,
    TIME_CONFLICT,
    SIGNATURE_VERIFICATION_FAILED,
    METADATA_RESOLUTION_FAILED,
    INTERNAL_FAILURE,
    UNSUPPORTED_SIGNING_HASH,
}

public sealed interface NativeOpenPgpCertificateMaterialReconcileResult {
    public class Success(
        val publicCertificate: ByteArray,
        val privateCertificate: ByteArray?,
        val primaryFingerprint: String,
        val existingPublicContributed: Boolean,
        val incomingPublicContributed: Boolean,
        val existingSecretContributed: Boolean,
        val incomingSecretContributed: Boolean,
    ) : NativeOpenPgpCertificateMaterialReconcileResult

    public data class Error(
        val failure: NativeOpenPgpCertificateMaterialReconcileFailure,
    ) : NativeOpenPgpCertificateMaterialReconcileResult
}

public sealed interface NativeOpenPgpCertificateMaterialReconcileV2Result {
    public class Success(
        val localPublicMaterial: ByteArray,
        val localSecretMaterial: ByteArray?,
        val transferablePublicCertificate: ByteArray?,
        val transferableSecretKey: ByteArray?,
        val primaryFingerprint: String,
        val contributions: NativeOpenPgpCertificateMaterialContributions,
        val withheldReasons: Set<NativeOpenPgpCertificateMaterialWithheldReason>,
    ) : NativeOpenPgpCertificateMaterialReconcileV2Result

    public data class Error(
        val failure: NativeOpenPgpCertificateMaterialReconcileFailure,
    ) : NativeOpenPgpCertificateMaterialReconcileV2Result
}

public data class NativeOpenPgpCertificateMaterialContributions(
    val existingPublic: NativeOpenPgpCertificateMaterialInputContribution,
    val incomingPublic: NativeOpenPgpCertificateMaterialInputContribution,
    val existingSecret: NativeOpenPgpCertificateMaterialInputContribution,
    val incomingSecret: NativeOpenPgpCertificateMaterialInputContribution,
)

public data class NativeOpenPgpCertificateMaterialInputContribution(
    val present: Boolean,
    val uniquePublicEvidence: Boolean,
    val uniqueSecretCapability: Boolean,
)

public enum class NativeOpenPgpCertificateMaterialWithheldReason {
    NO_TRANSFERABLE_PUBLIC_CERTIFICATE,
    LOCAL_PUBLIC_EVIDENCE,
    SECRET_MATERIAL_NOT_TRANSFERABLE,
}

public sealed interface NativeOpenPgpCertificateMaterialReconcileFailure {
    public data class InvalidInputs(
        val existingPublic: NativeOpenPgpCertificateMaterialInputError?,
        val incomingPublic: NativeOpenPgpCertificateMaterialInputError?,
        val existingSecret: NativeOpenPgpCertificateMaterialInputError?,
        val incomingSecret: NativeOpenPgpCertificateMaterialInputError?,
    ) : NativeOpenPgpCertificateMaterialReconcileFailure

    public data class Pair(
        val reason: NativeOpenPgpCertificateMaterialPairError,
    ) : NativeOpenPgpCertificateMaterialReconcileFailure
}

public enum class NativeOpenPgpCertificateMaterialInputError {
    EMPTY_CERTIFICATE,
    MALFORMED_CERTIFICATE,
    UNSUPPORTED_KEY_VERSION,
    FINGERPRINT_MISMATCH,
    COMPONENT_COLLISION,
    RESOURCE_LIMIT,
    UNSUPPORTED_TSK_LAYOUT,
}

public enum class NativeOpenPgpCertificateMaterialPairError {
    MISSING_MATERIAL,
    FINGERPRINT_MISMATCH,
    COMPONENT_COLLISION,
    RESOURCE_LIMIT,
    INVALID_REBUILT_OUTPUT,
    CONFLICTING_SECRET_MATERIAL,
}

public sealed interface NativeOpenPgpUserIdRevocationResult {
    public class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
        val certificateIndex: NativeOpenPgpCertificateIndex,
        /**
         * Minimal transferable public certificate containing the revocation.
         * Empty when unchanged or when the mutation is local-only.
         */
        val revocationCertificateArmored: ByteArray,
        /**
         * False when the same effective revocation was already present. A local-only
         * change has this set to true while [revocationCertificateArmored] is empty.
         */
        val changed: Boolean,
        val effectiveAtEpochSeconds: Long,
    ) : NativeOpenPgpUserIdRevocationResult

    public data class Error(
        val reason: NativeOpenPgpUserIdRevocationError,
    ) : NativeOpenPgpUserIdRevocationResult
}

public enum class NativeOpenPgpUserIdRevocationError {
    EMPTY_PRIVATE_KEY,
    MALFORMED_KEY,
    FINGERPRINT_MISMATCH,
    TARGET_NOT_FOUND,
    LAST_USER_ID,
    UNSUPPORTED_KEY_VERSION,
    PROTECTED_SECRET_KEY,
    MISSING_SELF_SIGNATURE,
    NON_REVOCABLE,
    TIME_CONFLICT,
    SIGNATURE_VERIFICATION_FAILED,
    METADATA_RESOLUTION_FAILED,
    INTERNAL_FAILURE,
    CERTIFICATE_REVOKED,
    UNRESOLVED_REVOCATION_AUTHORITY,
    UNSUPPORTED_SIGNING_HASH,
}

public sealed interface NativeOpenPgpUserIdReplacementResult {
    public class Success(
        val keyMaterial: NativeOpenPgpKeyMaterial,
        val certificateIndex: NativeOpenPgpCertificateIndex,
        /**
         * Minimal transferable certificate containing both replacement statements.
         * Empty when unchanged or when the mutation is local-only.
         */
        val replacementCertificateArmored: ByteArray,
        /**
         * False when the exact replacement was already effective. A local-only
         * change has this set to true while [replacementCertificateArmored] is empty.
         */
        val changed: Boolean,
        val effectiveAtEpochSeconds: Long,
        val oldIdentityId: String,
        val newIdentityId: String,
        val primaryUserId: String,
    ) : NativeOpenPgpUserIdReplacementResult

    public data class Error(
        val reason: NativeOpenPgpUserIdReplacementError,
    ) : NativeOpenPgpUserIdReplacementResult
}

public enum class NativeOpenPgpUserIdReplacementError {
    EMPTY_PRIVATE_KEY,
    MALFORMED_KEY,
    FINGERPRINT_MISMATCH,
    TARGET_NOT_FOUND,
    TARGET_INACTIVE,
    INVALID_NEW_USER_ID,
    SAME_IDENTITY,
    DUPLICATE_IDENTITY,
    PREVIOUSLY_REVOKED_IDENTITY,
    AMBIGUOUS_PRIMARY,
    UNSUPPORTED_KEY_VERSION,
    PROTECTED_SECRET_KEY,
    MISSING_SELF_SIGNATURE,
    NON_REVOCABLE,
    UNSUPPORTED_TEMPLATE,
    TIME_CONFLICT,
    SIGNATURE_VERIFICATION_FAILED,
    METADATA_RESOLUTION_FAILED,
    INTERNAL_FAILURE,
    CERTIFICATE_REVOKED,
    UNRESOLVED_REVOCATION_AUTHORITY,
    UNSUPPORTED_SIGNING_HASH,

    /** Authenticated certificate policy is ambiguous; advancing the clock cannot resolve it. */
    POLICY_CONFLICT,
}

public sealed interface NativeOpenPgpAgentSignResult {
    public class Success(
        val canonicalSexp: ByteArray,
    ) : NativeOpenPgpAgentSignResult

    public data class Error(
        val reason: NativeOpenPgpAgentError,
    ) : NativeOpenPgpAgentSignResult
}

public sealed interface NativeOpenPgpAgentDecryptResult {
    public class Success(
        val canonicalSexp: ByteArray,
    ) : NativeOpenPgpAgentDecryptResult

    public data class Error(
        val reason: NativeOpenPgpAgentError,
    ) : NativeOpenPgpAgentDecryptResult
}

public enum class NativeOpenPgpAgentError {
    KEY_NOT_FOUND,
    UNSUPPORTED_ALGORITHM,
}

public interface NativeOpenPgpVerificationSession : AutoCloseable {
    /** Supplies at most 64 KiB of the detached signature's body. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    /** Finishes and consumes this session, returning the authenticated result. */
    public fun finish(): NativeOpenPgpVerification

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpClearVerificationSession : AutoCloseable {
    /**
     * Supplies at most 64 KiB of the cleartext-signed document and returns
     * the dash-unescaped body bytes recovered so far. The structural line
     * ending before the signature armor and unauthenticated trailing spaces
     * and tabs are excluded. Line endings are preserved as received; the
     * signature covers only the CRLF-canonical form of the text, so their
     * exact bytes are attacker-malleable even in a valid document (GnuPG
     * and RNP recover them the same way). The returned plaintext is
     * provisional until [finish] succeeds.
     */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes and consumes this session, returning the authenticated result. */
    public fun finish(): NativeOpenPgpClearVerifyResult

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpDetachedSigningSession : AutoCloseable {
    /** Supplies at most 64 KiB of the exact file body to sign. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    )

    /** Finishes and consumes this session, returning the detached signature. */
    public fun finish(): ByteArray

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpClearSigningSession : AutoCloseable {
    /**
     * Supplies at most 64 KiB of UTF-8 cleartext and returns the next
     * cleartext-signature framework chunk. A contiguous run of spaces and
     * tabs across updates is limited to 64 KiB.
     */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes and consumes this session, returning the armored signature trailer. */
    public fun finish(): ByteArray

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpEncryptionSession : AutoCloseable {
    /** Supplies plaintext and returns the next encrypted output chunk. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Finishes and consumes this session, returning final bytes and the selected mode. */
    public fun finish(): NativeOpenPgpEncryptFinal

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

public interface NativeOpenPgpDecryptionSession : AutoCloseable {
    /** Supplies encrypted input and returns provisional plaintext for caller-owned staging. */
    public fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    /** Authenticates, consumes this session, and returns final provisional plaintext. */
    public fun finish(): NativeOpenPgpDecryptFinal

    /** Releases this session. This operation is idempotent. */
    override fun close()
}

/** Safe, application-internal facade for the OpenPGP read path. */
public object NativeCryptoOpenPgp {
    /** Maximum number of key documents accepted by one native OpenPGP request. */
    public const val MAX_KEY_DOCUMENTS_PER_REQUEST: Int = 64

    /**
     * Maximum plaintext accepted by the in-memory [encrypt] and returned by [decrypt].
     * One MiB of the 16 MiB native envelope remains available for the encoded final chunk and
     * verification metadata. Larger payloads must use [openEncryption] or [openDecryption].
     */
    public const val MAX_IN_MEMORY_PLAINTEXT_BYTES: Int = 15 * 1024 * 1024

    private const val RSA_3072_KEY_BITS: Int = 3072
    private const val RSA_4096_KEY_BITS: Int = 4096

    /**
     * RSA modulus sizes accepted by the native OpenPGP key generator. This is
     * the source of truth for user-facing key size options; anything offered in
     * the UI must be present in this set.
     */
    public val SUPPORTED_RSA_KEY_BITS: Set<Int> = setOf(
        RSA_3072_KEY_BITS,
        RSA_4096_KEY_BITS,
    )

    public fun parsePublicKeys(
        keyData: ByteArray,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpPublicKeyParseResult {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_public_key_parse",
            operation = OpenPgpPublicKeyParseOperationProto(
                OpenPgpPublicKeyParseRequestProto(
                    keyData = keyData,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_public_key_parse")
        return decodeOpenPgpPublicKeyParseResult("open_pgp_public_key_parse", payload)
    }

    public fun verifyClearSigned(
        signedDocument: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerification = verify(
        kind = OpenPgpVerifyKindProto.CLEAR_TEXT,
        content = signedDocument,
        signature = byteArrayOf(),
        publicKeys = publicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun verifyDetached(
        content: ByteArray,
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerification = verify(
        kind = OpenPgpVerifyKindProto.DETACHED,
        content = content,
        signature = signature,
        publicKeys = publicKeys,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun openDetachedVerification(
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpVerificationSession {
        requireReferenceTime(referenceTimeEpochSeconds)
        val session = NativeCrypto.openPgpDetachedVerification(
            signature = signature,
            publicKeys = publicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpVerificationSessionImpl(session)
    }

    public fun openClearVerification(
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpClearVerificationSession {
        requireReferenceTime(referenceTimeEpochSeconds)
        val session = NativeCrypto.openPgpClearVerification(
            publicKeys = publicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpClearVerificationSessionImpl(session)
    }

    public fun resolveMetadata(
        privateKeyData: ByteArray?,
        publicKeyData: ByteArray?,
        normalizedFingerprint: String = "",
        candidateRevocationKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpMetadataResolution? {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_metadata_resolve",
            operation = OpenPgpMetadataResolveOperationProto(
                OpenPgpMetadataResolveRequestProto(
                    privateKeyData = privateKeyData,
                    publicKeyData = publicKeyData,
                    normalizedFingerprint = normalizedFingerprint,
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_metadata_resolve")
        return decodeOpenPgpMetadataResolution(
            operation = "open_pgp_metadata_resolve",
            payload = payload,
        )
    }

    public fun generateKey(
        kind: NativeOpenPgpKeyKind,
        userId: String,
        rsaBits: Int = 0,
        creationTimeEpochSeconds: Long,
        expirationSeconds: Long? = null,
    ): NativeOpenPgpKeyMaterial {
        require(userId.isNotBlank()) { "OpenPGP user ID must not be blank" }
        require(creationTimeEpochSeconds >= 0L) { "OpenPGP creation time must not be negative" }
        require(expirationSeconds == null || expirationSeconds in 1L..UInt.MAX_VALUE.toLong()) {
            "OpenPGP expiration must fit an unsigned 32-bit duration"
        }
        when (kind) {
            NativeOpenPgpKeyKind.LEGACY_ED25519_X25519 ->
                require(rsaBits == 0) { "RSA bits must be zero for a modern OpenPGP key" }

            NativeOpenPgpKeyKind.RSA ->
                require(rsaBits in SUPPORTED_RSA_KEY_BITS) {
                    "Unsupported OpenPGP RSA size"
                }
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_key_generate",
            operation = OpenPgpKeyGenerateOperationProto(
                OpenPgpKeyGenerateRequestProto(
                    kind = when (kind) {
                        NativeOpenPgpKeyKind.LEGACY_ED25519_X25519 ->
                            OpenPgpKeyKindProto.LEGACY_ED25519_X25519

                        NativeOpenPgpKeyKind.RSA -> OpenPgpKeyKindProto.RSA
                    },
                    userId = userId,
                    rsaBits = rsaBits,
                    creationTimeEpochSeconds = creationTimeEpochSeconds,
                    expirationSeconds = expirationSeconds?.toUInt(),
                ),
            ),
        ).requireBytes("open_pgp_key_generate")
        return decodePayload<OpenPgpKeyMaterialProto>(
            operation = "open_pgp_key_generate",
            payload = payload,
        ).toPublic(
            operation = "open_pgp_key_generate",
            requirePrivateKey = true,
        )
    }

    public fun importKey(
        keyData: ByteArray,
        passphraseUtf8: ByteArray? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpKeyImportResult {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_key_import",
            operation = OpenPgpKeyImportOperationProto(
                OpenPgpKeyImportRequestProto(
                    keyData = keyData,
                    passphraseUtf8 = passphraseUtf8,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_key_import")
        val result = decodePayload<OpenPgpKeyImportResultProto>(
            operation = "open_pgp_key_import",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpKeyImportSuccessOutcomeProto -> {
                val material = outcome.value.keyMaterial
                    ?: malformedOpenPgp("open_pgp_key_import")
                NativeOpenPgpKeyImportResult.Success(
                    material.toPublic(
                        operation = "open_pgp_key_import",
                        requirePrivateKey = true,
                    ),
                )
            }

            is OpenPgpKeyImportNeedsPassphraseOutcomeProto -> {
                val formatLabel = outcome.value.formatLabel
                if (formatLabel.isBlank()) malformedOpenPgp("open_pgp_key_import")
                NativeOpenPgpKeyImportResult.NeedsPassphrase(formatLabel)
            }

            is OpenPgpKeyImportErrorOutcomeProto -> NativeOpenPgpKeyImportResult.Error(
                reason = when (outcome.value.reason) {
                    OpenPgpKeyImportErrorReasonProto.EMPTY -> NativeOpenPgpKeyImportError.EMPTY
                    OpenPgpKeyImportErrorReasonProto.UNSUPPORTED_FORMAT ->
                        NativeOpenPgpKeyImportError.UNSUPPORTED_FORMAT

                    OpenPgpKeyImportErrorReasonProto.INVALID_PASSPHRASE ->
                        NativeOpenPgpKeyImportError.INVALID_PASSPHRASE

                    OpenPgpKeyImportErrorReasonProto.MALFORMED_KEY ->
                        NativeOpenPgpKeyImportError.MALFORMED_KEY

                    OpenPgpKeyImportErrorReasonProto.UNSPECIFIED ->
                        malformedOpenPgp("open_pgp_key_import")
                },
            )

            null -> malformedOpenPgp("open_pgp_key_import")
        }
    }

    public fun clearSign(
        content: ByteArray,
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String = "",
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): ByteArray = sign(
        kind = OpenPgpSignKindProto.CLEAR_TEXT,
        content = content,
        privateKey = privateKey,
        candidateRevocationKeys = candidateRevocationKeys,
        preferredFingerprint = preferredFingerprint,
        armored = true,
        signatureTimeEpochSeconds = signatureTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun signDetached(
        content: ByteArray,
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String = "",
        armored: Boolean = true,
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): ByteArray = sign(
        kind = OpenPgpSignKindProto.DETACHED,
        content = content,
        privateKey = privateKey,
        candidateRevocationKeys = candidateRevocationKeys,
        preferredFingerprint = preferredFingerprint,
        armored = armored,
        signatureTimeEpochSeconds = signatureTimeEpochSeconds,
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    )

    public fun encrypt(
        content: ByteArray,
        publicKeys: List<ByteArray>,
        candidateRevocationKeys: List<ByteArray>,
        signingPrivateKey: ByteArray? = null,
        preferredSigningFingerprint: String = "",
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
        enableCompression: Boolean = true,
    ): NativeOpenPgpEncryptResult {
        requireEncryptInputs(
            publicKeys = publicKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        requireInMemoryOpenPgpPlaintextSize(
            operation = "open_pgp_encrypt",
            size = content.size,
        )
        if (content.size > OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES) {
            return encryptStreaming(
                content = content,
                publicKeys = publicKeys,
                candidateRevocationKeys = candidateRevocationKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = armored,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                enableCompression = enableCompression,
            )
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_encrypt",
            operation = OpenPgpEncryptOperationProto(
                OpenPgpEncryptRequestProto(
                    content = content,
                    publicKeys = publicKeys,
                    signingPrivateKey = signingPrivateKey,
                    preferredSigningFingerprint = preferredSigningFingerprint,
                    fileName = fileName,
                    armored = armored,
                    literalTimeEpochSeconds = literalTimeEpochSeconds,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                    enableCompression = enableCompression,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            ),
        ).requireBytes("open_pgp_encrypt")
        return decodePayload<OpenPgpEncryptResultProto>(
            operation = "open_pgp_encrypt",
            payload = payload,
        ).toPublic("open_pgp_encrypt")
    }

    private fun encryptStreaming(
        content: ByteArray,
        publicKeys: List<ByteArray>,
        candidateRevocationKeys: List<ByteArray>,
        signingPrivateKey: ByteArray?,
        preferredSigningFingerprint: String,
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
        enableCompression: Boolean,
    ): NativeOpenPgpEncryptResult {
        val session = openEncryption(
            publicKeys = publicKeys,
            candidateRevocationKeys = candidateRevocationKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            armored = armored,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            enableCompression = enableCompression,
        )
        val stagedOutputs = mutableListOf<ByteArray>()
        var totalOutputSize = 0L
        var primaryFailure: Throwable? = null
        var resultData: ByteArray? = null
        val result = try {
            content.forEachStreamChunk { offset, length ->
                totalOutputSize = stageOutput(
                    operation = "open_pgp_encrypt.stream_update",
                    output = session.update(content, offset, length),
                    stagedOutputs = stagedOutputs,
                    previousTotal = totalOutputSize,
                )
            }

            val final = session.finish()
            totalOutputSize = stageOutput(
                operation = "open_pgp_encrypt.stream_update",
                output = final.data,
                stagedOutputs = stagedOutputs,
                previousTotal = totalOutputSize,
            )
            NativeOpenPgpEncryptResult(
                data = mergeStagedOutputs(
                    stagedOutputs = stagedOutputs,
                    totalOutputSize = totalOutputSize.toInt(),
                ).also { resultData = it },
                protectionMode = final.protectionMode,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            resultData?.fill(0)
            throw failure
        } finally {
            stagedOutputs.forEach { output -> output.fill(0) }
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    resultData?.fill(0)
                    primaryFailure = closeFailure
                }
            }
        }
        primaryFailure?.let { throw it }
        return result
    }

    /**
     * Decrypts an OpenPGP message. When [allowSignedOnly] is true, unencrypted
     * inline-signed messages are also accepted; unsigned literal packets are
     * always rejected.
     */
    public fun decrypt(
        content: ByteArray,
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
        allowSignedOnly: Boolean = false,
    ): NativeOpenPgpDecryptResult {
        requireDecryptInputs(
            privateKeys = privateKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            allowSignedOnly = allowSignedOnly,
        )
        if (content.isEmpty()) {
            throw NativeCryptoException(
                operation = "open_pgp_decrypt",
                code = NativeCryptoErrorCode.INVALID_ARGUMENT,
            )
        }
        return decryptStreaming(
            content = content,
            privateKeys = privateKeys,
            verificationPublicKeys = verificationPublicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            allowSignedOnly = allowSignedOnly,
        )
    }

    private fun decryptStreaming(
        content: ByteArray,
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
        allowSignedOnly: Boolean,
    ): NativeOpenPgpDecryptResult {
        val session = openDecryption(
            privateKeys = privateKeys,
            verificationPublicKeys = verificationPublicKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            allowSignedOnly = allowSignedOnly,
        )
        val stagedPlaintext = BoundedOpenPgpPlaintextAccumulator(
            operation = "open_pgp_decrypt",
            maximumBytes = MAX_IN_MEMORY_PLAINTEXT_BYTES,
        )
        var primaryFailure: Throwable? = null
        var resultData: ByteArray? = null
        var deferredCloseFailure: Throwable? = null
        val result = try {
            content.forEachStreamChunk { offset, length ->
                stagedPlaintext.stage(session.update(content, offset, length))
            }

            val final = session.finish()
            stagedPlaintext.stage(final.data)
            NativeOpenPgpDecryptResult(
                data = stagedPlaintext.commit().also { resultData = it },
                verification = final.verification,
                metadata = final.metadata,
                encrypted = final.encrypted,
                declaredCharset = final.declaredCharset,
                decryptionKeyFingerprint = final.decryptionKeyFingerprint,
                warnings = final.warnings,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            resultData?.fill(0)
            throw failure
        } finally {
            stagedPlaintext.close()
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    resultData?.fill(0)
                    deferredCloseFailure = closeFailure
                }
            }
        }
        return deferredCloseFailure?.let { throw it } ?: result
    }

    public fun openDetachedSigning(
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String = "",
        armored: Boolean = true,
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpDetachedSigningSession {
        requireSigningInputs(
            privateKey = privateKey,
            preferredFingerprint = preferredFingerprint,
            signatureTimeEpochSeconds = signatureTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpDetachedSigningSessionImpl(
            NativeCrypto.openPgpDetachedSigning(
                privateKey = privateKey,
                candidateRevocationKeys = candidateRevocationKeys,
                preferredFingerprint = preferredFingerprint,
                armored = armored,
                signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        )
    }

    public fun openClearSigning(
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String = "",
        signatureTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
    ): NativeOpenPgpClearSigningSession {
        requireSigningInputs(
            privateKey = privateKey,
            preferredFingerprint = preferredFingerprint,
            signatureTimeEpochSeconds = signatureTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpClearSigningSessionImpl(
            NativeCrypto.openPgpClearSigning(
                privateKey = privateKey,
                candidateRevocationKeys = candidateRevocationKeys,
                preferredFingerprint = preferredFingerprint,
                signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ),
        )
    }

    public fun openEncryption(
        publicKeys: List<ByteArray>,
        candidateRevocationKeys: List<ByteArray>,
        signingPrivateKey: ByteArray? = null,
        preferredSigningFingerprint: String = "",
        fileName: String,
        armored: Boolean,
        literalTimeEpochSeconds: Long? = null,
        referenceTimeEpochSeconds: Long? = null,
        enableCompression: Boolean = true,
    ): NativeOpenPgpEncryptionSession {
        requireEncryptInputs(
            publicKeys = publicKeys,
            signingPrivateKey = signingPrivateKey,
            preferredSigningFingerprint = preferredSigningFingerprint,
            fileName = fileName,
            literalTimeEpochSeconds = literalTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        return NativeOpenPgpEncryptionSessionImpl(
            NativeCrypto.openPgpEncryption(
                publicKeys = publicKeys,
                candidateRevocationKeys = candidateRevocationKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = armored,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                enableCompression = enableCompression,
            ),
        )
    }

    /**
     * Opens a decryption stream. When [allowSignedOnly] is true, unencrypted
     * inline-signed messages are also accepted; unsigned literal packets are
     * always rejected.
     */
    public fun openDecryption(
        privateKeys: List<ByteArray>,
        verificationPublicKeys: List<ByteArray> = emptyList(),
        referenceTimeEpochSeconds: Long? = null,
        allowSignedOnly: Boolean = false,
    ): NativeOpenPgpDecryptionSession {
        requireDecryptInputs(
            privateKeys = privateKeys,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            allowSignedOnly = allowSignedOnly,
        )
        return NativeOpenPgpDecryptionSessionImpl(
            NativeCrypto.openPgpDecryption(
                privateKeys = privateKeys,
                verificationPublicKeys = verificationPublicKeys,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                allowSignedOnly = allowSignedOnly,
            ),
        )
    }

    public fun updateExpiration(
        privateKey: ByteArray,
        publicKey: ByteArray,
        expectedPrimaryFingerprint: String,
        componentFingerprints: List<String>,
        expiresAtEpochSeconds: Long?,
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ): NativeOpenPgpExpirationUpdateResult {
        require(referenceTimeEpochSeconds >= 0L) {
            "OpenPGP reference time must not be negative"
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds >= 0L) {
            "OpenPGP expiration time must not be negative"
        }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_expiration_update",
            operation = OpenPgpExpirationUpdateOperationProto(
                OpenPgpExpirationUpdateRequestProto(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                    componentFingerprints = componentFingerprints,
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_expiration_update")
        val result = decodePayload<OpenPgpExpirationUpdateResultProto>(
            operation = "open_pgp_expiration_update",
            payload = payload,
        )
        return result.toPublicExpirationUpdateResult("open_pgp_expiration_update")
    }

    /** Unions public evidence and secret components from two logical certificate sides. */
    public fun reconcileCertificateMaterial(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: ByteArray?,
        incomingPublicCertificate: ByteArray?,
        existingSecretCertificate: ByteArray?,
        incomingSecretCertificate: ByteArray?,
    ): NativeOpenPgpCertificateMaterialReconcileResult {
        require(expectedPrimaryFingerprint.isNotEmpty()) {
            "Expected OpenPGP primary fingerprint must not be empty"
        }
        requirePreferredFingerprint(expectedPrimaryFingerprint)
        val operation = "open_pgp_certificate_material_reconcile"
        val payload =
            NativeCrypto
                .call(
                    operationName = operation,
                    operation =
                        OpenPgpCertificateMaterialReconcileOperationProto(
                            OpenPgpCertificateMaterialReconcileRequestProto(
                                expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                                existingPublicCertificate = existingPublicCertificate,
                                incomingPublicCertificate = incomingPublicCertificate,
                                existingSecretCertificate = existingSecretCertificate,
                                incomingSecretCertificate = incomingSecretCertificate,
                            ),
                        ),
                ).requireBytes(operation)
        return decodePayload<OpenPgpCertificateMaterialReconcileResultProto>(
            operation = operation,
            payload = payload,
        ).toPublicCertificateMaterialReconcileResult(
            operation = operation,
            expectedPrimaryFingerprint = expectedPrimaryFingerprint,
            privateOutputRequired =
                existingSecretCertificate != null ||
                    incomingSecretCertificate != null,
        )
    }

    /**
     * Unions local certificate evidence while exposing ordinary transferable
     * public and secret objects through separate optional fields.
     */
    public fun reconcileCertificateMaterialV2(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: ByteArray?,
        incomingPublicCertificate: ByteArray?,
        existingSecretCertificate: ByteArray?,
        incomingSecretCertificate: ByteArray?,
    ): NativeOpenPgpCertificateMaterialReconcileV2Result {
        require(expectedPrimaryFingerprint.isNotEmpty()) {
            "Expected OpenPGP primary fingerprint must not be empty"
        }
        requirePreferredFingerprint(expectedPrimaryFingerprint)
        val operation = "open_pgp_certificate_material_reconcile_v2"
        val payload =
            NativeCrypto
                .call(
                    operationName = operation,
                    operation =
                        OpenPgpCertificateMaterialReconcileV2OperationProto(
                            OpenPgpCertificateMaterialReconcileV2RequestProto(
                                expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                                existingPublicCertificate = existingPublicCertificate,
                                incomingPublicCertificate = incomingPublicCertificate,
                                existingSecretCertificate = existingSecretCertificate,
                                incomingSecretCertificate = incomingSecretCertificate,
                            ),
                        ),
                ).requireBytes(operation)
        return decodePayload<OpenPgpCertificateMaterialReconcileV2ResultProto>(
            operation = operation,
            payload = payload,
        ).toPublicCertificateMaterialReconcileV2Result(
            operation = operation,
            expectedPrimaryFingerprint = expectedPrimaryFingerprint,
            expectedInputPresence =
                listOf(
                    existingPublicCertificate != null,
                    incomingPublicCertificate != null,
                    existingSecretCertificate != null,
                    incomingSecretCertificate != null,
                ),
        )
    }

    /** Creates a signed certification revocation for one exact textual User ID. */
    public fun revokeUserId(
        privateKey: ByteArray,
        publicKey: ByteArray,
        expectedPrimaryFingerprint: String,
        identityId: String,
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ): NativeOpenPgpUserIdRevocationResult {
        require(referenceTimeEpochSeconds >= 0L) {
            "OpenPGP reference time must not be negative"
        }
        requirePreferredFingerprint(expectedPrimaryFingerprint)
        requireOpenPgpIdentityId(identityId)
        val operation = "open_pgp_user_id_revocation"
        val payload =
            NativeCrypto
                .call(
                    operationName = operation,
                    operation =
                        OpenPgpUserIdRevocationOperationProto(
                            OpenPgpUserIdRevocationRequestProto(
                                privateKey = privateKey,
                                publicKey = publicKey,
                                expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                                identityId = identityId,
                                candidateRevocationKeys = candidateRevocationKeys,
                                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                            ),
                        ),
                ).requireBytes(operation)
        return decodePayload<OpenPgpUserIdRevocationResultProto>(
            operation = operation,
            payload = payload,
        ).toPublicUserIdRevocationResult(
            operation = operation,
            expectedPrimaryFingerprint = expectedPrimaryFingerprint,
        )
    }

    /** Self-certifies [newUserId] and retires [oldIdentityId] atomically. */
    public fun replaceUserId(
        privateKey: ByteArray,
        publicKey: ByteArray,
        expectedPrimaryFingerprint: String,
        oldIdentityId: String,
        newUserId: String,
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ): NativeOpenPgpUserIdReplacementResult {
        require(referenceTimeEpochSeconds >= 0L) {
            "OpenPGP reference time must not be negative"
        }
        requirePreferredFingerprint(expectedPrimaryFingerprint)
        requireOpenPgpIdentityId(oldIdentityId)
        val operation = "open_pgp_user_id_replacement"
        val payload =
            NativeCrypto
                .call(
                    operationName = operation,
                    operation =
                        OpenPgpUserIdReplacementOperationProto(
                            OpenPgpUserIdReplacementRequestProto(
                                privateKey = privateKey,
                                publicKey = publicKey,
                                expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                                oldIdentityId = oldIdentityId,
                                newUserId = newUserId,
                                candidateRevocationKeys = candidateRevocationKeys,
                                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                            ),
                        ),
                ).requireBytes(operation)
        return decodePayload<OpenPgpUserIdReplacementResultProto>(
            operation = operation,
            payload = payload,
        ).toPublicUserIdReplacementResult(
            operation = operation,
            expectedPrimaryFingerprint = expectedPrimaryFingerprint,
        )
    }

    public fun agentSignHash(
        privateKey: ByteArray,
        preferredFingerprint: String,
        hashAlgorithm: String,
        hash: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
    ): NativeOpenPgpAgentSignResult {
        require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_agent_sign",
            operation = OpenPgpAgentSignOperationProto(
                OpenPgpAgentSignRequestProto(
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    hashAlgorithm = hashAlgorithm,
                    hash = hash,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            ),
        ).requireBytes("open_pgp_agent_sign")
        val result = decodePayload<OpenPgpAgentSignResultProto>(
            operation = "open_pgp_agent_sign",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpAgentSignSuccessOutcomeProto -> {
                if (outcome.value.canonicalSexp.isEmpty()) {
                    malformedOpenPgp("open_pgp_agent_sign")
                }
                NativeOpenPgpAgentSignResult.Success(outcome.value.canonicalSexp)
            }

            is OpenPgpAgentSignErrorOutcomeProto -> NativeOpenPgpAgentSignResult.Error(
                outcome.value.reason.toPublic("open_pgp_agent_sign"),
            )

            null -> malformedOpenPgp("open_pgp_agent_sign")
        }
    }

    public fun agentDecrypt(
        privateKey: ByteArray,
        preferredFingerprint: String,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): NativeOpenPgpAgentDecryptResult {
        require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
        val payload = NativeCrypto.call(
            operationName = "open_pgp_agent_decrypt",
            operation = OpenPgpAgentDecryptOperationProto(
                OpenPgpAgentDecryptRequestProto(
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    ciphertext = ciphertext,
                    unwrapEcdh = unwrapEcdh,
                ),
            ),
        ).requireBytes("open_pgp_agent_decrypt")
        val result = decodePayload<OpenPgpAgentDecryptResultProto>(
            operation = "open_pgp_agent_decrypt",
            payload = payload,
        )
        return when (val outcome = result.result) {
            is OpenPgpAgentDecryptSuccessOutcomeProto -> {
                if (outcome.value.canonicalSexp.isEmpty()) {
                    malformedOpenPgp("open_pgp_agent_decrypt")
                }
                NativeOpenPgpAgentDecryptResult.Success(outcome.value.canonicalSexp)
            }

            is OpenPgpAgentDecryptErrorOutcomeProto -> NativeOpenPgpAgentDecryptResult.Error(
                outcome.value.reason.toPublic("open_pgp_agent_decrypt"),
            )

            null -> malformedOpenPgp("open_pgp_agent_decrypt")
        }
    }

    private fun sign(
        kind: OpenPgpSignKindProto,
        content: ByteArray,
        privateKey: ByteArray,
        candidateRevocationKeys: List<ByteArray>,
        preferredFingerprint: String,
        armored: Boolean,
        signatureTimeEpochSeconds: Long?,
        referenceTimeEpochSeconds: Long?,
    ): ByteArray {
        requireSigningInputs(
            privateKey = privateKey,
            preferredFingerprint = preferredFingerprint,
            signatureTimeEpochSeconds = signatureTimeEpochSeconds,
            referenceTimeEpochSeconds = referenceTimeEpochSeconds,
        )
        val result = NativeCrypto.call(
            operationName = "open_pgp_sign",
            operation = OpenPgpSignOperationProto(
                OpenPgpSignRequestProto(
                    kind = kind,
                    content = content,
                    privateKey = privateKey,
                    preferredFingerprint = preferredFingerprint,
                    armored = armored,
                    signatureTimeEpochSeconds = signatureTimeEpochSeconds,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            ),
        ).requireBytes("open_pgp_sign")
        if (result.isEmpty()) malformedOpenPgp("open_pgp_sign")
        return result
    }

    private fun verify(
        kind: OpenPgpVerifyKindProto,
        content: ByteArray,
        signature: ByteArray,
        publicKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long?,
    ): NativeOpenPgpVerification {
        requireReferenceTime(referenceTimeEpochSeconds)
        val payload = NativeCrypto.call(
            operationName = "open_pgp_verify",
            operation = OpenPgpVerifyOperationProto(
                OpenPgpVerifyRequestProto(
                    kind = kind,
                    content = content,
                    signature = signature,
                    publicKeys = publicKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ),
            ),
        ).requireBytes("open_pgp_verify")
        return decodeOpenPgpVerification("open_pgp_verify", payload)
    }
}

internal class BoundedOpenPgpPlaintextAccumulator(
    private val operation: String,
    private val maximumBytes: Int,
) : AutoCloseable {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var closed = false

    init {
        require(maximumBytes >= 0) { "Maximum OpenPGP plaintext size must not be negative" }
    }

    /** Takes ownership of [output], including when the plaintext limit is exceeded. */
    fun stage(output: ByteArray) {
        check(!closed) { "OpenPGP plaintext accumulator is closed" }
        if (output.size > maximumBytes - size) {
            output.fill(0)
            clear()
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.RESOURCE_LIMIT,
            )
        }
        if (output.isNotEmpty()) {
            chunks += output
            size += output.size
        }
    }

    fun commit(): ByteArray {
        check(!closed) { "OpenPGP plaintext accumulator is closed" }
        var result: ByteArray? = null
        try {
            val destination = ByteArray(size)
            result = destination
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(destination, destinationOffset = offset)
                offset += chunk.size
            }
            return destination
        } catch (failure: Throwable) {
            result?.fill(0)
            throw failure
        } finally {
            clear()
            closed = true
        }
    }

    override fun close() {
        if (closed) return
        clear()
        closed = true
    }

    private fun clear() {
        chunks.forEach { chunk -> chunk.fill(0) }
        chunks.clear()
        size = 0
    }
}

private fun requireInMemoryOpenPgpPlaintextSize(
    operation: String,
    size: Int,
) {
    if (size > NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES) {
        throw NativeCryptoException(
            operation = operation,
            code = NativeCryptoErrorCode.RESOURCE_LIMIT,
        )
    }
}

/**
 * Keeps one-shot armored encryption comfortably below the 16 MiB response envelope after
 * base64 expansion and packet overhead. Larger payloads use the unbounded streaming transport.
 */
internal const val OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES: Int = 8 * 1024 * 1024

private class NativeOpenPgpVerificationSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpVerificationSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val output = delegate.update(data, offset, length)
        if (output.isNotEmpty()) {
            output.fill(0)
            malformedOpenPgp("open_pgp_detached_verify.stream_update")
        }
    }

    override fun finish(): NativeOpenPgpVerification = decodeOpenPgpVerification(
        operation = "open_pgp_detached_verify.stream_finish",
        payload = delegate.finish(),
    )

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpClearVerificationSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpClearVerificationSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): NativeOpenPgpClearVerifyResult {
        val operation = "open_pgp_clear_verify.stream_finish"
        val result = decodePayload<OpenPgpClearVerifyResultProto>(
            operation = operation,
            payload = delegate.finish(),
        )
        val verification = result.verification
            ?: malformedOpenPgp(operation)
        return NativeOpenPgpClearVerifyResult(
            verification = verification.toPublic(operation),
            bodyValidUtf8 = result.bodyValidUtf8,
        )
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpDetachedSigningSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpDetachedSigningSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val output = delegate.update(data, offset, length)
        if (output.isNotEmpty()) {
            output.fill(0)
            malformedOpenPgp("open_pgp_detached_sign.stream_update")
        }
    }

    override fun finish(): ByteArray = delegate.finish().also { signature ->
        if (signature.isEmpty()) malformedOpenPgp("open_pgp_detached_sign.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpClearSigningSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpClearSigningSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): ByteArray = delegate.finish().also { trailer ->
        if (trailer.isEmpty()) malformedOpenPgp("open_pgp_clear_sign.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpEncryptionSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpEncryptionSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): NativeOpenPgpEncryptFinal {
        val payload = delegate.finish()
        return decodePayload<OpenPgpEncryptFinalProto>(
            operation = "open_pgp_encrypt.stream_finish",
            payload = payload,
        ).toPublic("open_pgp_encrypt.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private class NativeOpenPgpDecryptionSessionImpl(
    private val delegate: NativeCryptoSession,
) : NativeOpenPgpDecryptionSession {
    override fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = delegate.update(data, offset, length)

    override fun finish(): NativeOpenPgpDecryptFinal {
        val payload = delegate.finish()
        return decodePayload<OpenPgpDecryptFinalProto>(
            operation = "open_pgp_decrypt.stream_finish",
            payload = payload,
        ).toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
    }

    override fun close() {
        delegate.close()
    }
}

private fun OpenPgpKeyMaterialProto.toPublic(
    operation: String,
    requirePrivateKey: Boolean,
): NativeOpenPgpKeyMaterial {
    var ownershipTransferred = false
    return try {
        requireOpenPgpFingerprint(operation, fingerprint)
        if (publicKeyArmored.isEmpty() || (requirePrivateKey && privateKeyArmored.isEmpty())) {
            malformedOpenPgp(operation)
        }
        NativeOpenPgpKeyMaterial(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        ).also {
            ownershipTransferred = true
        }
    } finally {
        if (!ownershipTransferred) clearSensitiveData()
    }
}

internal fun OpenPgpExpirationUpdateResultProto.toPublicExpirationUpdateResult(
    operation: String,
): NativeOpenPgpExpirationUpdateResult = when (val outcome = result) {
    is OpenPgpExpirationUpdateSuccessOutcomeProto -> {
        val keyMaterial = outcome.value.keyMaterial ?: malformedOpenPgp(operation)
        var ownershipTransferred = false
        try {
            val certificateIndex = outcome.value.certificateIndex?.toPublic(operation)
                ?: malformedOpenPgp(operation)
            NativeOpenPgpExpirationUpdateResult.Success(
                keyMaterial = keyMaterial.toPublic(
                    operation = operation,
                    requirePrivateKey = true,
                ),
                certificateIndex = certificateIndex,
            ).also {
                ownershipTransferred = true
            }
        } finally {
            if (!ownershipTransferred) keyMaterial.clearSensitiveData()
        }
    }

    is OpenPgpExpirationUpdateErrorOutcomeProto ->
        NativeOpenPgpExpirationUpdateResult.Error(
            reason = outcome.value.reason.toPublic(operation),
        )

    null -> malformedOpenPgp(operation)
}

private fun OpenPgpKeyMaterialProto.clearSensitiveData() {
    privateKeyArmored.fill(0)
    publicKeyArmored.fill(0)
}

private fun hasConsistentPrimaryFingerprint(
    keyMaterial: OpenPgpKeyMaterialProto,
    certificateIndex: NativeOpenPgpCertificateIndex,
    expectedPrimaryFingerprint: String,
): Boolean {
    val actualPrimaryFingerprint = keyMaterial.fingerprint
    if (actualPrimaryFingerprint != certificateIndex.primaryFingerprint) return false
    return expectedPrimaryFingerprint.isEmpty() ||
        actualPrimaryFingerprint == expectedPrimaryFingerprint
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod")
internal fun OpenPgpCertificateMaterialReconcileResultProto.toPublicCertificateMaterialReconcileResult(
    operation: String,
    expectedPrimaryFingerprint: String,
    privateOutputRequired: Boolean,
): NativeOpenPgpCertificateMaterialReconcileResult =
    when (val outcome = result) {
        is OpenPgpCertificateMaterialReconcileSuccessOutcomeProto -> {
            val value = outcome.value
            val privateCertificate = value.privateCertificate
            val invalid =
                value.publicCertificate.isEmpty() ||
                    value.primaryFingerprint != expectedPrimaryFingerprint ||
                    privateOutputRequired != (privateCertificate != null) ||
                    privateCertificate?.isEmpty() == true
            if (invalid) {
                value.publicCertificate.fill(0)
                privateCertificate?.fill(0)
                malformedOpenPgp(operation)
            }
            requireOpenPgpFingerprint(operation, value.primaryFingerprint)
            NativeOpenPgpCertificateMaterialReconcileResult.Success(
                publicCertificate = value.publicCertificate,
                privateCertificate = privateCertificate,
                primaryFingerprint = value.primaryFingerprint,
                existingPublicContributed = value.existingPublicContributed,
                incomingPublicContributed = value.incomingPublicContributed,
                existingSecretContributed = value.existingSecretContributed,
                incomingSecretContributed = value.incomingSecretContributed,
            )
        }

        is OpenPgpCertificateMaterialReconcileErrorOutcomeProto -> {
            val value = outcome.value
            val existingPublic = value.existingPublicInputError.toPublicCertificateInputError()
            val incomingPublic = value.incomingPublicInputError.toPublicCertificateInputError()
            val existingSecret = value.existingSecretInputError.toPublicCertificateInputError()
            val incomingSecret = value.incomingSecretInputError.toPublicCertificateInputError()
            val pair = value.pairError.toPublicCertificatePairError()
            val hasInputError =
                existingPublic != null ||
                    incomingPublic != null ||
                    existingSecret != null ||
                    incomingSecret != null
            if (hasInputError == (pair != null)) malformedOpenPgp(operation)
            NativeOpenPgpCertificateMaterialReconcileResult.Error(
                failure =
                    if (hasInputError) {
                        NativeOpenPgpCertificateMaterialReconcileFailure.InvalidInputs(
                            existingPublic = existingPublic,
                            incomingPublic = incomingPublic,
                            existingSecret = existingSecret,
                            incomingSecret = incomingSecret,
                        )
                    } else {
                        NativeOpenPgpCertificateMaterialReconcileFailure.Pair(
                            pair ?: malformedOpenPgp(operation),
                        )
                    },
            )
        }

        null -> {
            malformedOpenPgp(operation)
        }
    }

internal fun OpenPgpCertificateMaterialReconcileV2ResultProto
    .toPublicCertificateMaterialReconcileV2Result(
        operation: String,
        expectedPrimaryFingerprint: String,
        expectedInputPresence: List<Boolean>,
    ): NativeOpenPgpCertificateMaterialReconcileV2Result =
    when (val outcome = result) {
        is OpenPgpCertificateMaterialReconcileV2SuccessOutcomeProto -> {
            val value = outcome.value
            val localPublic = value.localPublicMaterial
            val localSecret = value.localSecretMaterial
            val transferablePublic = value.transferablePublicCertificate
            val transferableSecret = value.transferableSecretKey
            fun clearOwnedOutputs() {
                localPublic.fill(0)
                localSecret?.fill(0)
                transferablePublic?.fill(0)
                transferableSecret?.fill(0)
            }

            val contributionProto = value.contributions
            val contributionsPresent =
                contributionProto != null &&
                    contributionProto.existingPublic != null &&
                    contributionProto.incomingPublic != null &&
                    contributionProto.existingSecret != null &&
                    contributionProto.incomingSecret != null
            val withheld = value.withheldReasons
            val withheldSet = withheld.toSet()
            val hasNoTransferablePublicReason =
                OpenPgpCertificateMaterialWithheldReasonProto
                    .NO_TRANSFERABLE_PUBLIC_CERTIFICATE in withheldSet
            val hasLocalPublicReason =
                OpenPgpCertificateMaterialWithheldReasonProto.LOCAL_PUBLIC_EVIDENCE in withheldSet
            val hasSecretWithheldReason =
                OpenPgpCertificateMaterialWithheldReasonProto
                    .SECRET_MATERIAL_NOT_TRANSFERABLE in withheldSet
            val localPublicWithheld =
                transferablePublic != null && !localPublic.contentEquals(transferablePublic)
            val secretWithheld =
                localSecret != null &&
                    (transferableSecret == null || !localSecret.contentEquals(transferableSecret))
            val hasSecretInput =
                expectedInputPresence.size == 4 &&
                    (expectedInputPresence[2] || expectedInputPresence[3])
            val invalid =
                expectedInputPresence.size != 4 ||
                    localPublic.isEmpty() ||
                    localSecret?.isEmpty() == true ||
                    transferablePublic?.isEmpty() == true ||
                    transferableSecret?.isEmpty() == true ||
                    value.primaryFingerprint != expectedPrimaryFingerprint ||
                    hasSecretInput != (localSecret != null) ||
                    transferableSecret != null &&
                    (localSecret == null || transferablePublic == null) ||
                    !contributionsPresent ||
                    withheld.size != withheldSet.size ||
                    OpenPgpCertificateMaterialWithheldReasonProto.UNSPECIFIED in withheldSet ||
                    hasNoTransferablePublicReason != (transferablePublic == null) ||
                    hasLocalPublicReason != localPublicWithheld ||
                    hasSecretWithheldReason != secretWithheld
            if (invalid) {
                clearOwnedOutputs()
                malformedOpenPgp(operation)
            }
            requireOpenPgpFingerprint(operation, value.primaryFingerprint)
            val contributions = contributionProto
            NativeOpenPgpCertificateMaterialReconcileV2Result.Success(
                localPublicMaterial = localPublic,
                localSecretMaterial = localSecret,
                transferablePublicCertificate = transferablePublic,
                transferableSecretKey = transferableSecret,
                primaryFingerprint = value.primaryFingerprint,
                contributions =
                    NativeOpenPgpCertificateMaterialContributions(
                        existingPublic =
                            contributions.existingPublic
                                .toPublicCertificateMaterialContribution(
                                    operation = operation,
                                    expectedPresent = expectedInputPresence[0],
                                    secretInput = false,
                                    clearOwnedOutputs = ::clearOwnedOutputs,
                                ),
                        incomingPublic =
                            contributions.incomingPublic
                                .toPublicCertificateMaterialContribution(
                                    operation = operation,
                                    expectedPresent = expectedInputPresence[1],
                                    secretInput = false,
                                    clearOwnedOutputs = ::clearOwnedOutputs,
                                ),
                        existingSecret =
                            contributions.existingSecret
                                .toPublicCertificateMaterialContribution(
                                    operation = operation,
                                    expectedPresent = expectedInputPresence[2],
                                    secretInput = true,
                                    clearOwnedOutputs = ::clearOwnedOutputs,
                                ),
                        incomingSecret =
                            contributions.incomingSecret
                                .toPublicCertificateMaterialContribution(
                                    operation = operation,
                                    expectedPresent = expectedInputPresence[3],
                                    secretInput = true,
                                    clearOwnedOutputs = ::clearOwnedOutputs,
                                ),
                    ),
                withheldReasons =
                    withheldSet.mapTo(mutableSetOf()) { reason ->
                        reason.toPublicCertificateMaterialWithheldReason(operation)
                    },
            )
        }

        is OpenPgpCertificateMaterialReconcileV2ErrorOutcomeProto -> {
            val value = outcome.value
            val existingPublic = value.existingPublicInputError.toPublicCertificateInputError()
            val incomingPublic = value.incomingPublicInputError.toPublicCertificateInputError()
            val existingSecret = value.existingSecretInputError.toPublicCertificateInputError()
            val incomingSecret = value.incomingSecretInputError.toPublicCertificateInputError()
            val pair = value.pairError.toPublicCertificatePairError()
            val hasInputError =
                existingPublic != null ||
                    incomingPublic != null ||
                    existingSecret != null ||
                    incomingSecret != null
            if (hasInputError == (pair != null)) malformedOpenPgp(operation)
            NativeOpenPgpCertificateMaterialReconcileV2Result.Error(
                failure =
                    if (hasInputError) {
                        NativeOpenPgpCertificateMaterialReconcileFailure.InvalidInputs(
                            existingPublic = existingPublic,
                            incomingPublic = incomingPublic,
                            existingSecret = existingSecret,
                            incomingSecret = incomingSecret,
                        )
                    } else {
                        NativeOpenPgpCertificateMaterialReconcileFailure.Pair(
                            pair ?: malformedOpenPgp(operation),
                        )
                    },
            )
        }

        null -> malformedOpenPgp(operation)
    }

private fun OpenPgpCertificateMaterialInputContributionProto
    .toPublicCertificateMaterialContribution(
        operation: String,
        expectedPresent: Boolean,
        secretInput: Boolean,
        clearOwnedOutputs: () -> Unit,
    ): NativeOpenPgpCertificateMaterialInputContribution {
    if (
        present != expectedPresent ||
        !present && (uniquePublicEvidence || uniqueSecretCapability) ||
        !secretInput && uniqueSecretCapability
    ) {
        clearOwnedOutputs()
        malformedOpenPgp(operation)
    }
    return NativeOpenPgpCertificateMaterialInputContribution(
        present = present,
        uniquePublicEvidence = uniquePublicEvidence,
        uniqueSecretCapability = uniqueSecretCapability,
    )
}

private fun OpenPgpCertificateMaterialWithheldReasonProto
    .toPublicCertificateMaterialWithheldReason(
        operation: String,
    ): NativeOpenPgpCertificateMaterialWithheldReason =
    when (this) {
        OpenPgpCertificateMaterialWithheldReasonProto.NO_TRANSFERABLE_PUBLIC_CERTIFICATE -> {
            NativeOpenPgpCertificateMaterialWithheldReason.NO_TRANSFERABLE_PUBLIC_CERTIFICATE
        }

        OpenPgpCertificateMaterialWithheldReasonProto.LOCAL_PUBLIC_EVIDENCE -> {
            NativeOpenPgpCertificateMaterialWithheldReason.LOCAL_PUBLIC_EVIDENCE
        }

        OpenPgpCertificateMaterialWithheldReasonProto.SECRET_MATERIAL_NOT_TRANSFERABLE -> {
            NativeOpenPgpCertificateMaterialWithheldReason.SECRET_MATERIAL_NOT_TRANSFERABLE
        }

        OpenPgpCertificateMaterialWithheldReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
    }

private fun OpenPgpCertificateMaterialInputErrorReasonProto
    .toPublicCertificateInputError(): NativeOpenPgpCertificateMaterialInputError? =
    when (this) {
        OpenPgpCertificateMaterialInputErrorReasonProto.EMPTY_CERTIFICATE -> {
            NativeOpenPgpCertificateMaterialInputError.EMPTY_CERTIFICATE
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.MALFORMED_CERTIFICATE -> {
            NativeOpenPgpCertificateMaterialInputError.MALFORMED_CERTIFICATE
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.UNSUPPORTED_KEY_VERSION -> {
            NativeOpenPgpCertificateMaterialInputError.UNSUPPORTED_KEY_VERSION
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.FINGERPRINT_MISMATCH -> {
            NativeOpenPgpCertificateMaterialInputError.FINGERPRINT_MISMATCH
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.COMPONENT_COLLISION -> {
            NativeOpenPgpCertificateMaterialInputError.COMPONENT_COLLISION
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.RESOURCE_LIMIT -> {
            NativeOpenPgpCertificateMaterialInputError.RESOURCE_LIMIT
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.UNSUPPORTED_TSK_LAYOUT -> {
            NativeOpenPgpCertificateMaterialInputError.UNSUPPORTED_TSK_LAYOUT
        }

        OpenPgpCertificateMaterialInputErrorReasonProto.UNSPECIFIED -> {
            null
        }
    }

private fun OpenPgpCertificateMaterialPairErrorReasonProto
    .toPublicCertificatePairError(): NativeOpenPgpCertificateMaterialPairError? =
    when (this) {
        OpenPgpCertificateMaterialPairErrorReasonProto.MISSING_MATERIAL -> {
            NativeOpenPgpCertificateMaterialPairError.MISSING_MATERIAL
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.FINGERPRINT_MISMATCH -> {
            NativeOpenPgpCertificateMaterialPairError.FINGERPRINT_MISMATCH
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.COMPONENT_COLLISION -> {
            NativeOpenPgpCertificateMaterialPairError.COMPONENT_COLLISION
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.RESOURCE_LIMIT -> {
            NativeOpenPgpCertificateMaterialPairError.RESOURCE_LIMIT
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.INVALID_REBUILT_OUTPUT -> {
            NativeOpenPgpCertificateMaterialPairError.INVALID_REBUILT_OUTPUT
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.CONFLICTING_SECRET_MATERIAL -> {
            NativeOpenPgpCertificateMaterialPairError.CONFLICTING_SECRET_MATERIAL
        }

        OpenPgpCertificateMaterialPairErrorReasonProto.UNSPECIFIED -> {
            null
        }
    }

internal fun OpenPgpUserIdRevocationResultProto.toPublicUserIdRevocationResult(
    operation: String,
    expectedPrimaryFingerprint: String,
): NativeOpenPgpUserIdRevocationResult =
    when (val outcome = result) {
        is OpenPgpUserIdRevocationSuccessOutcomeProto -> {
            val value = outcome.value
            val keyMaterial = value.keyMaterial ?: malformedOpenPgp(operation)
            var ownershipTransferred = false
            try {
                val certificateIndex =
                    value.certificateIndex?.toPublic(operation)
                        ?: malformedOpenPgp(operation)
                requireOpenPgpEpoch(operation, value.effectiveAtEpochSeconds)
                if (!value.changed && value.revocationCertificateArmored.isNotEmpty()) {
                    malformedOpenPgp(operation)
                }
                if (
                    !hasConsistentPrimaryFingerprint(
                        keyMaterial = keyMaterial,
                        certificateIndex = certificateIndex,
                        expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                    )
                ) {
                    malformedOpenPgp(operation)
                }
                NativeOpenPgpUserIdRevocationResult
                    .Success(
                        keyMaterial =
                            keyMaterial.toPublic(
                                operation = operation,
                                requirePrivateKey = true,
                            ),
                        certificateIndex = certificateIndex,
                        revocationCertificateArmored = value.revocationCertificateArmored,
                        changed = value.changed,
                        effectiveAtEpochSeconds = value.effectiveAtEpochSeconds,
                    ).also {
                        ownershipTransferred = true
                    }
            } finally {
                if (!ownershipTransferred) {
                    keyMaterial.clearSensitiveData()
                    value.revocationCertificateArmored.fill(0)
                }
            }
        }

        is OpenPgpUserIdRevocationErrorOutcomeProto -> {
            NativeOpenPgpUserIdRevocationResult.Error(
                outcome.value.reason.toPublicUserIdRevocationError(operation),
            )
        }

        null -> {
            malformedOpenPgp(operation)
        }
    }

@Suppress("CyclomaticComplexMethod")
private fun OpenPgpUserIdRevocationErrorReasonProto.toPublicUserIdRevocationError(
    operation: String,
): NativeOpenPgpUserIdRevocationError =
    when (this) {
        OpenPgpUserIdRevocationErrorReasonProto.EMPTY_PRIVATE_KEY -> {
            NativeOpenPgpUserIdRevocationError.EMPTY_PRIVATE_KEY
        }

        OpenPgpUserIdRevocationErrorReasonProto.MALFORMED_KEY -> {
            NativeOpenPgpUserIdRevocationError.MALFORMED_KEY
        }

        OpenPgpUserIdRevocationErrorReasonProto.FINGERPRINT_MISMATCH -> {
            NativeOpenPgpUserIdRevocationError.FINGERPRINT_MISMATCH
        }

        OpenPgpUserIdRevocationErrorReasonProto.TARGET_NOT_FOUND -> {
            NativeOpenPgpUserIdRevocationError.TARGET_NOT_FOUND
        }

        OpenPgpUserIdRevocationErrorReasonProto.LAST_USER_ID -> {
            NativeOpenPgpUserIdRevocationError.LAST_USER_ID
        }

        OpenPgpUserIdRevocationErrorReasonProto.UNSUPPORTED_KEY_VERSION -> {
            NativeOpenPgpUserIdRevocationError.UNSUPPORTED_KEY_VERSION
        }

        OpenPgpUserIdRevocationErrorReasonProto.PROTECTED_SECRET_KEY -> {
            NativeOpenPgpUserIdRevocationError.PROTECTED_SECRET_KEY
        }

        OpenPgpUserIdRevocationErrorReasonProto.MISSING_SELF_SIGNATURE -> {
            NativeOpenPgpUserIdRevocationError.MISSING_SELF_SIGNATURE
        }

        OpenPgpUserIdRevocationErrorReasonProto.NON_REVOCABLE -> {
            NativeOpenPgpUserIdRevocationError.NON_REVOCABLE
        }

        OpenPgpUserIdRevocationErrorReasonProto.TIME_CONFLICT -> {
            NativeOpenPgpUserIdRevocationError.TIME_CONFLICT
        }

        OpenPgpUserIdRevocationErrorReasonProto.SIGNATURE_VERIFICATION_FAILED -> {
            NativeOpenPgpUserIdRevocationError.SIGNATURE_VERIFICATION_FAILED
        }

        OpenPgpUserIdRevocationErrorReasonProto.METADATA_RESOLUTION_FAILED -> {
            NativeOpenPgpUserIdRevocationError.METADATA_RESOLUTION_FAILED
        }

        OpenPgpUserIdRevocationErrorReasonProto.INTERNAL_FAILURE -> {
            NativeOpenPgpUserIdRevocationError.INTERNAL_FAILURE
        }

        OpenPgpUserIdRevocationErrorReasonProto.CERTIFICATE_REVOKED -> {
            NativeOpenPgpUserIdRevocationError.CERTIFICATE_REVOKED
        }

        OpenPgpUserIdRevocationErrorReasonProto.UNRESOLVED_REVOCATION_AUTHORITY -> {
            NativeOpenPgpUserIdRevocationError.UNRESOLVED_REVOCATION_AUTHORITY
        }

        OpenPgpUserIdRevocationErrorReasonProto.UNSUPPORTED_SIGNING_HASH -> {
            NativeOpenPgpUserIdRevocationError.UNSUPPORTED_SIGNING_HASH
        }

        OpenPgpUserIdRevocationErrorReasonProto.UNSPECIFIED -> {
            malformedOpenPgp(operation)
        }
    }

internal fun OpenPgpUserIdReplacementResultProto.toPublicUserIdReplacementResult(
    operation: String,
    expectedPrimaryFingerprint: String,
): NativeOpenPgpUserIdReplacementResult =
    when (val outcome = result) {
        is OpenPgpUserIdReplacementSuccessOutcomeProto -> {
            val value = outcome.value
            val keyMaterial = value.keyMaterial ?: malformedOpenPgp(operation)
            var ownershipTransferred = false
            try {
                val certificateIndex =
                    value.certificateIndex?.toPublic(operation)
                        ?: malformedOpenPgp(operation)
                requireOpenPgpEpoch(operation, value.effectiveAtEpochSeconds)
                if (!value.changed && value.replacementCertificateArmored.isNotEmpty()) {
                    malformedOpenPgp(operation)
                }
                requireOpenPgpIdentityId(operation, value.oldIdentityId)
                requireOpenPgpIdentityId(operation, value.newIdentityId)
                if (
                    !value.primaryUserId.isValidOpenPgpUserId() ||
                    !hasConsistentPrimaryFingerprint(
                        keyMaterial = keyMaterial,
                        certificateIndex = certificateIndex,
                        expectedPrimaryFingerprint = expectedPrimaryFingerprint,
                    )
                ) {
                    malformedOpenPgp(operation)
                }
                NativeOpenPgpUserIdReplacementResult
                    .Success(
                        keyMaterial =
                            keyMaterial.toPublic(
                                operation = operation,
                                requirePrivateKey = true,
                            ),
                        certificateIndex = certificateIndex,
                        replacementCertificateArmored = value.replacementCertificateArmored,
                        changed = value.changed,
                        effectiveAtEpochSeconds = value.effectiveAtEpochSeconds,
                        oldIdentityId = value.oldIdentityId,
                        newIdentityId = value.newIdentityId,
                        primaryUserId = value.primaryUserId,
                    ).also {
                        ownershipTransferred = true
                    }
            } finally {
                if (!ownershipTransferred) {
                    keyMaterial.clearSensitiveData()
                    value.replacementCertificateArmored.fill(0)
                }
            }
        }

        is OpenPgpUserIdReplacementErrorOutcomeProto -> {
            NativeOpenPgpUserIdReplacementResult.Error(
                outcome.value.reason.toPublicUserIdReplacementError(operation),
            )
        }

        null -> {
            malformedOpenPgp(operation)
        }
    }

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun OpenPgpUserIdReplacementErrorReasonProto.toPublicUserIdReplacementError(
    operation: String,
): NativeOpenPgpUserIdReplacementError =
    when (this) {
        OpenPgpUserIdReplacementErrorReasonProto.EMPTY_PRIVATE_KEY -> {
            NativeOpenPgpUserIdReplacementError.EMPTY_PRIVATE_KEY
        }

        OpenPgpUserIdReplacementErrorReasonProto.MALFORMED_KEY -> {
            NativeOpenPgpUserIdReplacementError.MALFORMED_KEY
        }

        OpenPgpUserIdReplacementErrorReasonProto.FINGERPRINT_MISMATCH -> {
            NativeOpenPgpUserIdReplacementError.FINGERPRINT_MISMATCH
        }

        OpenPgpUserIdReplacementErrorReasonProto.TARGET_NOT_FOUND -> {
            NativeOpenPgpUserIdReplacementError.TARGET_NOT_FOUND
        }

        OpenPgpUserIdReplacementErrorReasonProto.TARGET_INACTIVE -> {
            NativeOpenPgpUserIdReplacementError.TARGET_INACTIVE
        }

        OpenPgpUserIdReplacementErrorReasonProto.INVALID_NEW_USER_ID -> {
            NativeOpenPgpUserIdReplacementError.INVALID_NEW_USER_ID
        }

        OpenPgpUserIdReplacementErrorReasonProto.SAME_IDENTITY -> {
            NativeOpenPgpUserIdReplacementError.SAME_IDENTITY
        }

        OpenPgpUserIdReplacementErrorReasonProto.DUPLICATE_IDENTITY -> {
            NativeOpenPgpUserIdReplacementError.DUPLICATE_IDENTITY
        }

        OpenPgpUserIdReplacementErrorReasonProto.PREVIOUSLY_REVOKED_IDENTITY -> {
            NativeOpenPgpUserIdReplacementError.PREVIOUSLY_REVOKED_IDENTITY
        }

        OpenPgpUserIdReplacementErrorReasonProto.AMBIGUOUS_PRIMARY -> {
            NativeOpenPgpUserIdReplacementError.AMBIGUOUS_PRIMARY
        }

        OpenPgpUserIdReplacementErrorReasonProto.UNSUPPORTED_KEY_VERSION -> {
            NativeOpenPgpUserIdReplacementError.UNSUPPORTED_KEY_VERSION
        }

        OpenPgpUserIdReplacementErrorReasonProto.PROTECTED_SECRET_KEY -> {
            NativeOpenPgpUserIdReplacementError.PROTECTED_SECRET_KEY
        }

        OpenPgpUserIdReplacementErrorReasonProto.MISSING_SELF_SIGNATURE -> {
            NativeOpenPgpUserIdReplacementError.MISSING_SELF_SIGNATURE
        }

        OpenPgpUserIdReplacementErrorReasonProto.NON_REVOCABLE -> {
            NativeOpenPgpUserIdReplacementError.NON_REVOCABLE
        }

        OpenPgpUserIdReplacementErrorReasonProto.UNSUPPORTED_TEMPLATE -> {
            NativeOpenPgpUserIdReplacementError.UNSUPPORTED_TEMPLATE
        }

        OpenPgpUserIdReplacementErrorReasonProto.TIME_CONFLICT -> {
            NativeOpenPgpUserIdReplacementError.TIME_CONFLICT
        }

        OpenPgpUserIdReplacementErrorReasonProto.SIGNATURE_VERIFICATION_FAILED -> {
            NativeOpenPgpUserIdReplacementError.SIGNATURE_VERIFICATION_FAILED
        }

        OpenPgpUserIdReplacementErrorReasonProto.METADATA_RESOLUTION_FAILED -> {
            NativeOpenPgpUserIdReplacementError.METADATA_RESOLUTION_FAILED
        }

        OpenPgpUserIdReplacementErrorReasonProto.INTERNAL_FAILURE -> {
            NativeOpenPgpUserIdReplacementError.INTERNAL_FAILURE
        }

        OpenPgpUserIdReplacementErrorReasonProto.CERTIFICATE_REVOKED -> {
            NativeOpenPgpUserIdReplacementError.CERTIFICATE_REVOKED
        }

        OpenPgpUserIdReplacementErrorReasonProto.UNRESOLVED_REVOCATION_AUTHORITY -> {
            NativeOpenPgpUserIdReplacementError.UNRESOLVED_REVOCATION_AUTHORITY
        }

        OpenPgpUserIdReplacementErrorReasonProto.UNSUPPORTED_SIGNING_HASH -> {
            NativeOpenPgpUserIdReplacementError.UNSUPPORTED_SIGNING_HASH
        }

        OpenPgpUserIdReplacementErrorReasonProto.POLICY_CONFLICT -> {
            NativeOpenPgpUserIdReplacementError.POLICY_CONFLICT
        }

        OpenPgpUserIdReplacementErrorReasonProto.UNSPECIFIED -> {
            malformedOpenPgp(operation)
        }
    }

private fun OpenPgpExpirationUpdateErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpExpirationUpdateError = when (this) {
    OpenPgpExpirationUpdateErrorReasonProto.EMPTY_PRIVATE_KEY ->
        NativeOpenPgpExpirationUpdateError.EMPTY_PRIVATE_KEY

    OpenPgpExpirationUpdateErrorReasonProto.MALFORMED_KEY ->
        NativeOpenPgpExpirationUpdateError.MALFORMED_KEY

    OpenPgpExpirationUpdateErrorReasonProto.FINGERPRINT_MISMATCH ->
        NativeOpenPgpExpirationUpdateError.FINGERPRINT_MISMATCH

    OpenPgpExpirationUpdateErrorReasonProto.NO_COMPONENTS_SELECTED ->
        NativeOpenPgpExpirationUpdateError.NO_COMPONENTS_SELECTED

    OpenPgpExpirationUpdateErrorReasonProto.COMPONENT_NOT_FOUND ->
        NativeOpenPgpExpirationUpdateError.COMPONENT_NOT_FOUND

    OpenPgpExpirationUpdateErrorReasonProto.REVOKED_COMPONENT ->
        NativeOpenPgpExpirationUpdateError.REVOKED_COMPONENT

    OpenPgpExpirationUpdateErrorReasonProto.UNRESOLVED_REVOCATION_AUTHORITY ->
        NativeOpenPgpExpirationUpdateError.UNRESOLVED_REVOCATION_AUTHORITY

    OpenPgpExpirationUpdateErrorReasonProto.UNSUPPORTED_KEY_VERSION ->
        NativeOpenPgpExpirationUpdateError.UNSUPPORTED_KEY_VERSION

    OpenPgpExpirationUpdateErrorReasonProto.MISSING_SECRET_KEY ->
        NativeOpenPgpExpirationUpdateError.MISSING_SECRET_KEY

    OpenPgpExpirationUpdateErrorReasonProto.PROTECTED_SECRET_KEY ->
        NativeOpenPgpExpirationUpdateError.PROTECTED_SECRET_KEY

    OpenPgpExpirationUpdateErrorReasonProto.MISSING_SELF_SIGNATURE ->
        NativeOpenPgpExpirationUpdateError.MISSING_SELF_SIGNATURE

    OpenPgpExpirationUpdateErrorReasonProto.INVALID_EXPIRATION ->
        NativeOpenPgpExpirationUpdateError.INVALID_EXPIRATION

    OpenPgpExpirationUpdateErrorReasonProto.TIME_CONFLICT ->
        NativeOpenPgpExpirationUpdateError.TIME_CONFLICT

    OpenPgpExpirationUpdateErrorReasonProto.SIGNATURE_VERIFICATION_FAILED ->
        NativeOpenPgpExpirationUpdateError.SIGNATURE_VERIFICATION_FAILED

    OpenPgpExpirationUpdateErrorReasonProto.METADATA_RESOLUTION_FAILED ->
        NativeOpenPgpExpirationUpdateError.METADATA_RESOLUTION_FAILED

    OpenPgpExpirationUpdateErrorReasonProto.INTERNAL_FAILURE ->
        NativeOpenPgpExpirationUpdateError.INTERNAL_FAILURE

    OpenPgpExpirationUpdateErrorReasonProto.UNSUPPORTED_SIGNING_HASH ->
        NativeOpenPgpExpirationUpdateError.UNSUPPORTED_SIGNING_HASH

    OpenPgpExpirationUpdateErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpAgentErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpAgentError = when (this) {
    OpenPgpAgentErrorReasonProto.KEY_NOT_FOUND -> NativeOpenPgpAgentError.KEY_NOT_FOUND
    OpenPgpAgentErrorReasonProto.UNSUPPORTED_ALGORITHM ->
        NativeOpenPgpAgentError.UNSUPPORTED_ALGORITHM

    OpenPgpAgentErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpProtectionModeProto.toPublic(
    operation: String,
): NativeOpenPgpProtectionMode = when (this) {
    OpenPgpProtectionModeProto.SEIPD_V1_MDC -> NativeOpenPgpProtectionMode.SEIPD_V1_MDC
    OpenPgpProtectionModeProto.GNUPG_OCB -> NativeOpenPgpProtectionMode.GNUPG_OCB
    OpenPgpProtectionModeProto.SEIPD_V2_AEAD -> NativeOpenPgpProtectionMode.SEIPD_V2_AEAD
    OpenPgpProtectionModeProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

private fun OpenPgpEncryptResultProto.toPublic(
    operation: String,
): NativeOpenPgpEncryptResult {
    if (data.isEmpty()) malformedOpenPgp(operation)
    return NativeOpenPgpEncryptResult(
        data = data,
        protectionMode = protectionMode.toPublic(operation),
    )
}

private fun OpenPgpEncryptFinalProto.toPublic(
    operation: String,
): NativeOpenPgpEncryptFinal = NativeOpenPgpEncryptFinal(
    data = data,
    protectionMode = protectionMode.toPublic(operation),
)

internal fun OpenPgpDecryptFinalProto.toPublicDecryptFinal(
    operation: String,
): NativeOpenPgpDecryptFinal {
    var ownershipTransferred = false
    return try {
        val publicWarnings = warnings.map { wireValue ->
            when (OpenPgpDecryptionWarningProto.fromWireValue(wireValue)) {
                OpenPgpDecryptionWarningProto.WEAK_RSA_KEY ->
                    NativeOpenPgpDecryptionWarning.WEAK_RSA_KEY

                OpenPgpDecryptionWarningProto.ELGAMAL_KEY ->
                    NativeOpenPgpDecryptionWarning.ELGAMAL_KEY

                OpenPgpDecryptionWarningProto.UNSPECIFIED,
                null,
                -> malformedOpenPgp(operation)
            }
        }
        if (publicWarnings.toSet().size != publicWarnings.size) {
            malformedOpenPgp(operation)
        }
        decryptionKeyFingerprint?.let { fingerprint ->
            requireOpenPgpFingerprint(operation, fingerprint)
        }
        if (!encrypted && (decryptionKeyFingerprint != null || publicWarnings.isNotEmpty())) {
            malformedOpenPgp(operation)
        }
        NativeOpenPgpDecryptFinal(
            data = data,
            verification = verification?.toPublic(operation),
            metadata = metadata?.toPublic(operation),
            encrypted = encrypted,
            declaredCharset = declaredCharset,
            decryptionKeyFingerprint = decryptionKeyFingerprint,
            warnings = publicWarnings,
        ).also {
            ownershipTransferred = true
        }
    } finally {
        if (!ownershipTransferred) data.fill(0)
    }
}

private fun OpenPgpLiteralMetadataProto.toPublic(
    operation: String,
): NativeOpenPgpLiteralMetadata {
    if (format !in 0..UByte.MAX_VALUE.toInt() ||
        modificationTimeEpochSeconds < 0L ||
        originalSize < 0L
    ) {
        malformedOpenPgp(operation)
    }
    return NativeOpenPgpLiteralMetadata(
        fileName = fileName,
        format = format,
        modificationTimeEpochSeconds = modificationTimeEpochSeconds,
        originalSize = originalSize,
    )
}

private fun OpenPgpPublicKeyInfoProto.toPublic(
    operation: String,
): NativeOpenPgpPublicKeyInfo {
    requireOpenPgpFingerprint(operation, fingerprint)
    requireOpenPgpKeygrip(operation, keygrip)
    requireOpenPgpKeyId(operation, keyId)
    requireOpenPgpAlgorithm(operation, algorithm)
    requireOpenPgpBitStrength(operation, bitStrength)
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    requireOpenPgpEpoch(operation, expiresAtEpochSeconds)
    if (publicKeyArmored.isEmpty()) malformedOpenPgp(operation)
    return NativeOpenPgpPublicKeyInfo(
        fingerprint = fingerprint,
        keygrip = keygrip,
        keyId = keyId,
        algorithm = algorithm,
        bitStrength = bitStrength,
        userIds = userIds,
        emails = emails,
        createdAtEpochSeconds = createdAtEpochSeconds,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        revoked = revoked,
        canSign = canSign,
        canEncrypt = canEncrypt,
        publicKeyArmored = publicKeyArmored,
        subkeys = subkeys.map { value -> value.toPublic(operation) },
        authenticated = authenticated,
        renewal = renewal.toRenewalAuthorizationOrNone(),
    )
}

/**
 * A renewal value this build does not know must never look like
 * permission, so anything unrecognized degrades to NONE.
 */
private fun Int.toRenewalAuthorizationOrNone(): NativeOpenPgpRenewalAuthorization = when (this) {
    OPEN_PGP_RENEWAL_AUTHORIZATION_AUTHENTICATED ->
        NativeOpenPgpRenewalAuthorization.AUTHENTICATED

    OPEN_PGP_RENEWAL_AUTHORIZATION_TEMPLATE_ONLY ->
        NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY

    else -> NativeOpenPgpRenewalAuthorization.NONE
}

private fun OpenPgpPublicSubKeyInfoProto.toPublic(
    operation: String,
): NativeOpenPgpPublicSubKeyInfo {
    requireOpenPgpFingerprint(operation, fingerprint)
    requireOpenPgpKeygrip(operation, keygrip)
    requireOpenPgpKeyId(operation, keyId)
    requireOpenPgpAlgorithm(operation, algorithm)
    requireOpenPgpBitStrength(operation, bitStrength)
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    requireOpenPgpEpoch(operation, expiresAtEpochSeconds)
    return NativeOpenPgpPublicSubKeyInfo(
        fingerprint = fingerprint,
        keygrip = keygrip,
        keyId = keyId,
        algorithm = algorithm,
        bitStrength = bitStrength,
        canSign = canSign,
        canEncrypt = canEncrypt,
        revoked = revoked,
        createdAtEpochSeconds = createdAtEpochSeconds,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        authenticated = authenticated,
    )
}

private fun OpenPgpPublicKeyParseErrorReasonProto.toPublic(
    operation: String,
): NativeOpenPgpPublicKeyParseError = when (this) {
    OpenPgpPublicKeyParseErrorReasonProto.EMPTY -> NativeOpenPgpPublicKeyParseError.EMPTY
    OpenPgpPublicKeyParseErrorReasonProto.MALFORMED -> NativeOpenPgpPublicKeyParseError.MALFORMED
    OpenPgpPublicKeyParseErrorReasonProto.UNSUPPORTED_KEY_VERSION ->
        NativeOpenPgpPublicKeyParseError.UNSUPPORTED_KEY_VERSION

    OpenPgpPublicKeyParseErrorReasonProto.MULTIPLE_CERTIFICATES ->
        NativeOpenPgpPublicKeyParseError.MULTIPLE_CERTIFICATES

    OpenPgpPublicKeyParseErrorReasonProto.UNSPECIFIED -> malformedOpenPgp(operation)
}

internal fun decodeOpenPgpPublicKeyParseResult(
    operation: String,
    payload: ByteArray,
): NativeOpenPgpPublicKeyParseResult {
    val result = decodePayload<OpenPgpPublicKeyParseResultProto>(
        operation = operation,
        payload = payload,
    )
    return when (val outcome = result.result) {
        is OpenPgpPublicKeyParseSuccessOutcomeProto -> {
            val keys = outcome.value.keys.map { value -> value.toPublic(operation) }
            if (keys.isEmpty()) malformedOpenPgp(operation)
            val skipped = outcome.value.skippedCertificates
            if (skipped < 0) malformedOpenPgp(operation)
            NativeOpenPgpPublicKeyParseResult.Success(
                keys = keys,
                skippedCertificates = skipped,
            )
        }

        is OpenPgpPublicKeyParseErrorOutcomeProto ->
            NativeOpenPgpPublicKeyParseResult.Error(
                reason = outcome.value.reason.toPublic(operation),
            )

        null -> malformedOpenPgp(operation)
    }
}

internal fun decodeOpenPgpVerification(
    operation: String,
    payload: ByteArray,
): NativeOpenPgpVerification = decodePayload<OpenPgpVerificationProto>(
    operation = operation,
    payload = payload,
).toPublic(operation)

internal fun decodeOpenPgpMetadataResolution(
    operation: String,
    payload: ByteArray,
): NativeOpenPgpMetadataResolution? {
    val result = decodePayload<OpenPgpMetadataResolveResultProto>(
        operation = operation,
        payload = payload,
    )
    return result.resolution?.toPublic(operation)
}

private fun OpenPgpVerificationProto.toPublic(
    operation: String,
    allowSignatureResults: Boolean = true,
): NativeOpenPgpVerification {
    requireOpenPgpKeyId(operation, keyId)
    fingerprint?.let { value -> requireOpenPgpFingerprint(operation, value) }
    requireOpenPgpEpoch(operation, createdAtEpochSeconds)
    val publicStatus = when (status) {
        OpenPgpVerificationStatusProto.VALID -> NativeOpenPgpVerificationStatus.VALID
        OpenPgpVerificationStatusProto.INVALID -> NativeOpenPgpVerificationStatus.INVALID
        OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY ->
            NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY

        OpenPgpVerificationStatusProto.UNSPECIFIED -> malformedOpenPgp(operation)
    }
    val publicWarnings = warnings.map { wireValue ->
        when (
            OpenPgpVerificationWarningProto.fromWireValue(wireValue)
                ?: malformedOpenPgp(operation)
        ) {
            OpenPgpVerificationWarningProto.KEY_REVOKED ->
                NativeOpenPgpVerificationWarning.KEY_REVOKED

            OpenPgpVerificationWarningProto.KEY_EXPIRED ->
                NativeOpenPgpVerificationWarning.KEY_EXPIRED

            OpenPgpVerificationWarningProto.SIGNATURE_EXPIRED ->
                NativeOpenPgpVerificationWarning.SIGNATURE_EXPIRED

            OpenPgpVerificationWarningProto.POLICY_CONFLICT ->
                NativeOpenPgpVerificationWarning.POLICY_CONFLICT

            OpenPgpVerificationWarningProto.WEAK_DIGEST ->
                NativeOpenPgpVerificationWarning.WEAK_DIGEST

            OpenPgpVerificationWarningProto.UNSPECIFIED -> malformedOpenPgp(operation)
        }
    }
    if (publicWarnings.toSet().size != publicWarnings.size) {
        malformedOpenPgp(operation)
    }
    if (!allowSignatureResults && signatures.isNotEmpty()) {
        malformedOpenPgp(operation)
    }
    val publicSignatures = signatures.map { result ->
        result.toPublic(
            operation = operation,
            allowSignatureResults = false,
        )
    }
    when (publicStatus) {
        NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY -> {
            if (fingerprint != null || userIds.isNotEmpty() || publicWarnings.isNotEmpty()) {
                malformedOpenPgp(operation)
            }
        }

        NativeOpenPgpVerificationStatus.VALID,
        NativeOpenPgpVerificationStatus.INVALID,
            -> if (fingerprint == null) malformedOpenPgp(operation)
    }
    return NativeOpenPgpVerification(
        status = publicStatus,
        keyId = keyId,
        fingerprint = fingerprint,
        userIds = userIds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        warnings = publicWarnings,
        signatures = publicSignatures,
    )
}

private fun OpenPgpMetadataResolutionV2Proto.toPublic(
    operation: String,
): NativeOpenPgpMetadataResolution {
    if (evaluatedAtEpochSeconds < 0L || policyRevision <= 0 || certificates.isEmpty()) {
        malformedOpenPgp(operation)
    }
    val publicCertificates = certificates.map { certificate ->
        val index = certificate.index?.toPublic(operation) ?: malformedOpenPgp(operation)
        val componentFingerprints = index.components.mapTo(mutableSetOf()) { it.fingerprint }
        val seenPolicyFingerprints = mutableSetOf<String>()
        val publicPolicy = certificate.policy.map { value ->
            requireOpenPgpFingerprint(operation, value.fingerprint)
            if (
                value.fingerprint !in componentFingerprints ||
                !seenPolicyFingerprints.add(value.fingerprint)
            ) {
                malformedOpenPgp(operation)
            }
            val revocationStatus = if (policyRevision == OPEN_PGP_POLICY_REVISION_V2) {
                when (value.revocationStatus) {
                    OPEN_PGP_REVOCATION_STATUS_NOT_REVOKED -> NativeOpenPgpRevocationStatus.NOT_REVOKED
                    OPEN_PGP_REVOCATION_STATUS_REVOKED -> NativeOpenPgpRevocationStatus.REVOKED
                    else -> NativeOpenPgpRevocationStatus.INDETERMINATE
                }
            } else {
                NativeOpenPgpRevocationStatus.INDETERMINATE
            }
            NativeOpenPgpComponentPolicy(
                fingerprint = value.fingerprint,
                allowedNewDataUses = if (revocationStatus == NativeOpenPgpRevocationStatus.NOT_REVOKED) {
                    value.allowedNewDataUses.mapNotNullTo(mutableSetOf()) { wireValue ->
                        when (wireValue) {
                            OPEN_PGP_POLICY_USE_SIGN_NEW_DATA ->
                                NativeOpenPgpPolicyUse.SIGN_NEW_DATA

                            OPEN_PGP_POLICY_USE_ENCRYPT_NEW_DATA ->
                                NativeOpenPgpPolicyUse.ENCRYPT_NEW_DATA

                            else -> null
                        }
                    }
                } else {
                    emptySet()
                },
                // A policy revision this build does not understand must never
                // look like permission.
                renewal = if (revocationStatus == NativeOpenPgpRevocationStatus.NOT_REVOKED) {
                    value.renewal.toRenewalAuthorizationOrNone()
                } else {
                    NativeOpenPgpRenewalAuthorization.NONE
                },
                revocationStatus = revocationStatus,
            )
        }
        if (seenPolicyFingerprints != componentFingerprints) malformedOpenPgp(operation)
        NativeOpenPgpCertificateResolution(
            index = index,
            policy = publicPolicy,
        )
    }
    if (publicCertificates.map { it.index.primaryFingerprint }.toSet().size != publicCertificates.size) {
        malformedOpenPgp(operation)
    }
    return NativeOpenPgpMetadataResolution(
        certificates = publicCertificates,
        evaluatedAtEpochSeconds = evaluatedAtEpochSeconds,
        policyRevision = policyRevision,
    )
}

private fun OpenPgpCertificateIndexV2Proto.toPublic(
    operation: String,
): NativeOpenPgpCertificateIndex {
    requireOpenPgpFingerprint(operation, primaryFingerprint)
    if (components.isEmpty()) malformedOpenPgp(operation)
    val seenFingerprints = mutableSetOf<String>()
    val publicComponents = components.mapIndexed { index, value ->
        requireOpenPgpFingerprint(operation, value.fingerprint)
        requireOpenPgpAlgorithm(operation, value.algorithm)
        if (
            !seenFingerprints.add(value.fingerprint) ||
            value.publicKeyAlgorithmId !in 1..UByte.MAX_VALUE.toInt() ||
            value.keygrips.toSet().size != value.keygrips.size
        ) {
            malformedOpenPgp(operation)
        }
        value.keygrips.forEach { keygrip -> requireOpenPgpKeygrip(operation, keygrip) }
        val role = when (value.role) {
            OPEN_PGP_KEY_COMPONENT_ROLE_PRIMARY -> NativeOpenPgpKeyComponentRole.PRIMARY
            OPEN_PGP_KEY_COMPONENT_ROLE_SUBKEY -> NativeOpenPgpKeyComponentRole.SUBKEY
            else -> malformedOpenPgp(operation)
        }
        if (
            (index == 0) != (role == NativeOpenPgpKeyComponentRole.PRIMARY) ||
            (role == NativeOpenPgpKeyComponentRole.PRIMARY) !=
            (value.fingerprint == primaryFingerprint)
        ) {
            malformedOpenPgp(operation)
        }
        NativeOpenPgpKeyComponentIndex(
            fingerprint = value.fingerprint,
            role = role,
            publicKeyAlgorithmId = value.publicKeyAlgorithmId,
            algorithm = value.algorithm,
            keygrips = value.keygrips,
            storedSecretMaterial = value.storedSecretMaterial,
            agentOperations = value.agentOperations.mapNotNullTo(mutableSetOf()) { wireValue ->
                when (wireValue) {
                    OPEN_PGP_AGENT_OPERATION_SIGN -> NativeOpenPgpAgentOperation.SIGN
                    OPEN_PGP_AGENT_OPERATION_DECRYPT -> NativeOpenPgpAgentOperation.DECRYPT
                    else -> null
                }
            },
        )
    }
    val seenRevokers = mutableSetOf<Triple<Int, String, Int>>()
    val publicRevokers = legacyDesignatedRevokers.map { value ->
        requireOpenPgpFingerprint(operation, value.fingerprint)
        if (
            value.publicKeyAlgorithmId !in 1..UByte.MAX_VALUE.toInt() ||
            value.keyClass !in setOf(0x80, 0xC0) ||
            value.sensitive != (value.keyClass and 0x40 != 0) ||
            !seenRevokers.add(
                Triple(value.publicKeyAlgorithmId, value.fingerprint, value.keyClass),
            )
        ) {
            malformedOpenPgp(operation)
        }
        NativeOpenPgpLegacyDesignatedRevoker(
            publicKeyAlgorithmId = value.publicKeyAlgorithmId,
            fingerprint = value.fingerprint,
            keyClass = value.keyClass,
            sensitive = value.sensitive,
        )
    }
    return NativeOpenPgpCertificateIndex(
        primaryFingerprint = primaryFingerprint,
        components = publicComponents,
        legacyDesignatedRevokers = publicRevokers,
    )
}

private const val OPEN_PGP_POLICY_REVISION_V2 = 2
private const val OPEN_PGP_KEY_COMPONENT_ROLE_PRIMARY = 1
private const val OPEN_PGP_KEY_COMPONENT_ROLE_SUBKEY = 2
private const val OPEN_PGP_AGENT_OPERATION_SIGN = 1
private const val OPEN_PGP_AGENT_OPERATION_DECRYPT = 2
private const val OPEN_PGP_POLICY_USE_SIGN_NEW_DATA = 1
private const val OPEN_PGP_POLICY_USE_ENCRYPT_NEW_DATA = 2
private const val OPEN_PGP_RENEWAL_AUTHORIZATION_AUTHENTICATED = 1
private const val OPEN_PGP_RENEWAL_AUTHORIZATION_TEMPLATE_ONLY = 2
private const val OPEN_PGP_REVOCATION_STATUS_NOT_REVOKED = 1
private const val OPEN_PGP_REVOCATION_STATUS_REVOKED = 2

private inline fun <reified T> decodePayload(
    operation: String,
    payload: ByteArray,
): T = try {
    ProtoBuf.decodeFromByteArray<T>(payload)
} catch (_: SerializationException) {
    malformedOpenPgp(operation)
} catch (_: IllegalArgumentException) {
    malformedOpenPgp(operation)
} finally {
    payload.fill(0)
}

private fun requireReferenceTime(value: Long?) {
    require(value == null || value >= 0L) { "OpenPGP reference time must not be negative" }
}

private fun requireSigningInputs(
    privateKey: ByteArray,
    preferredFingerprint: String,
    signatureTimeEpochSeconds: Long?,
    referenceTimeEpochSeconds: Long?,
) {
    require(privateKey.isNotEmpty()) { "OpenPGP private key must not be empty" }
    requirePreferredFingerprint(preferredFingerprint)
    requireOptionalOpenPgpTime("signature", signatureTimeEpochSeconds)
    requireReferenceTime(referenceTimeEpochSeconds)
}

private fun requireEncryptInputs(
    publicKeys: List<ByteArray>,
    signingPrivateKey: ByteArray?,
    preferredSigningFingerprint: String,
    fileName: String,
    literalTimeEpochSeconds: Long?,
    referenceTimeEpochSeconds: Long?,
) {
    require(publicKeys.isNotEmpty() && publicKeys.all { key -> key.isNotEmpty() }) {
        "At least one non-empty OpenPGP public key is required"
    }
    require(signingPrivateKey == null || signingPrivateKey.isNotEmpty()) {
        "OpenPGP signing private key must not be empty"
    }
    require(signingPrivateKey != null || preferredSigningFingerprint.isEmpty()) {
        "A preferred signing fingerprint requires a signing private key"
    }
    requirePreferredFingerprint(preferredSigningFingerprint)
    require(fileName.isEmpty() || fileName.isNotBlank()) {
        "OpenPGP literal file name must be empty or non-blank"
    }
    requireOptionalOpenPgpTime("literal", literalTimeEpochSeconds)
    requireReferenceTime(referenceTimeEpochSeconds)
}

private fun requireDecryptInputs(
    privateKeys: List<ByteArray>,
    referenceTimeEpochSeconds: Long?,
    allowSignedOnly: Boolean,
) {
    require(
        (allowSignedOnly || privateKeys.isNotEmpty()) &&
                privateKeys.all { key -> key.isNotEmpty() },
    ) {
        "At least one non-empty OpenPGP private key is required"
    }
    requireReferenceTime(referenceTimeEpochSeconds)
}

/** Key IDs are 64-bit values rendered as upper-case hex. */
private const val OPEN_PGP_KEY_ID_HEX_CHARS: Int = 16

/** Keygrips are SHA-1 digests rendered as upper-case hex. */
private const val OPEN_PGP_KEYGRIP_HEX_CHARS: Int = 40

/** Accepts fingerprints from MD5 (32 hex chars) up to SHA-512 (128 hex chars). */
private const val OPEN_PGP_MIN_FINGERPRINT_HEX_CHARS: Int = 32
private const val OPEN_PGP_MAX_FINGERPRINT_HEX_CHARS: Int = 128

/** Maximum UTF-8 size accepted by the native User ID replacement operation. */
private const val OPEN_PGP_MAX_USER_ID_UTF8_BYTES: Int = 1_024

private const val OPEN_PGP_IDENTITY_ID_PREFIX = "v1:"
private const val OPEN_PGP_IDENTITY_ID_HEX_CHARS = 64

private fun requirePreferredFingerprint(value: String) {
    require(
        value.isEmpty() || value.isValidOpenPgpFingerprint(),
    ) { "Invalid preferred OpenPGP fingerprint" }
}

/** Applies the exact normalized-fingerprint constraints enforced by native OpenPGP adapters. */
public fun String.isValidOpenPgpFingerprint(): Boolean =
    length in OPEN_PGP_MIN_FINGERPRINT_HEX_CHARS..OPEN_PGP_MAX_FINGERPRINT_HEX_CHARS &&
        length % 2 == 0 &&
        isUpperHex()

private fun requireOpenPgpIdentityId(value: String) {
    require(value.isValidOpenPgpIdentityId()) {
        "OpenPGP identity ID must be the v1 uppercase SHA-256 hex form"
    }
}

private fun String.isValidOpenPgpIdentityId(): Boolean =
    hasOpenPgpIdentityIdEnvelope() &&
        drop(OPEN_PGP_IDENTITY_ID_PREFIX.length).isUpperHex()

private fun String.hasOpenPgpIdentityIdEnvelope(): Boolean =
    startsWith(OPEN_PGP_IDENTITY_ID_PREFIX) &&
        length == OPEN_PGP_IDENTITY_ID_PREFIX.length + OPEN_PGP_IDENTITY_ID_HEX_CHARS

/** Applies the exact input constraints enforced by native OpenPGP User ID replacement. */
public fun String.isValidOpenPgpUserId(): Boolean {
    if (isBlank() || any(Char::isISOControl)) return false
    val utf8Size =
        try {
            encodeToByteArray(throwOnInvalidSequence = true).size
        } catch (_: CharacterCodingException) {
            return false
        }
    return utf8Size <= OPEN_PGP_MAX_USER_ID_UTF8_BYTES
}

private fun requireOptionalOpenPgpTime(label: String, value: Long?) {
    require(value == null || value >= 0L) { "OpenPGP $label time must not be negative" }
}

private fun requireOpenPgpEpoch(operation: String, value: Long?) {
    if (value != null && value < 0L) malformedOpenPgp(operation)
}

private fun requireOpenPgpBitStrength(operation: String, value: Int?) {
    if (value != null && value <= 0) malformedOpenPgp(operation)
}

private fun requireOpenPgpAlgorithm(operation: String, value: String) {
    if (value.isEmpty()) malformedOpenPgp(operation)
}

private fun requireOpenPgpIdentityId(operation: String, value: String) {
    if (!value.isValidOpenPgpIdentityId()) malformedOpenPgp(operation)
}

private fun requireOpenPgpKeyId(operation: String, value: String) {
    if (value.length != OPEN_PGP_KEY_ID_HEX_CHARS || !value.isUpperHex()) {
        malformedOpenPgp(operation)
    }
}

private fun requireOpenPgpFingerprint(operation: String, value: String) {
    if (
        value.length !in OPEN_PGP_MIN_FINGERPRINT_HEX_CHARS..OPEN_PGP_MAX_FINGERPRINT_HEX_CHARS ||
        value.length % 2 != 0 ||
        !value.isUpperHex()
    ) {
        malformedOpenPgp(operation)
    }
}

private fun requireOpenPgpKeygrip(operation: String, value: String?) {
    if (value != null && (value.length != OPEN_PGP_KEYGRIP_HEX_CHARS || !value.isUpperHex())) {
        malformedOpenPgp(operation)
    }
}

private fun String.isUpperHex(): Boolean = all { character ->
    character in '0'..'9' || character in 'A'..'F'
}

private fun malformedOpenPgp(operation: String): Nothing = throw NativeCryptoException(
    operation = operation,
    code = NativeCryptoErrorCode.MALFORMED_RESPONSE,
)
