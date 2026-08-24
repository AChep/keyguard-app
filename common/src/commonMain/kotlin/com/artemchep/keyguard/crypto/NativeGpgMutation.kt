package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_INSTANT
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyNotFoundException
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentUnsupportedAlgorithmException
import com.artemchep.keyguard.common.service.gpgagent.GpgCanonicalSExpr
import com.artemchep.keyguard.common.service.gpgagent.GpgSExpr
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentDecryptResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentSignResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpExpirationUpdateError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpExpirationUpdateResult
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgKeyExpirationService : GpgKeyExpirationService {
    override fun update(
        request: GpgKeyExpirationRequest,
    ): GpgKeyExpirationResult = update(
        request = request,
        now = { Clock.System.now() },
    )

    internal fun update(
        request: GpgKeyExpirationRequest,
        now: () -> Instant,
    ): GpgKeyExpirationResult = try {
        val validationError = request.validationError(now)
        if (validationError != null) {
            GpgKeyExpirationResult.Error(validationError)
        } else {
            val referenceTimeEpochSeconds = now().epochSeconds
            request.withNativeInput(referenceTimeEpochSeconds) { input ->
                NativeCrypto.openPgp.updateExpiration(
                    privateKey = input.privateKey,
                    publicKey = input.publicKey,
                    expectedPrimaryFingerprint = input.expectedPrimaryFingerprint,
                    componentFingerprints = input.componentFingerprints,
                    expiresAtEpochSeconds = input.expiresAtEpochSeconds,
                    candidateRevocationKeys = input.candidateRevocationKeys,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                )
            }.toDomain()
        }
    } catch (_: Exception) {
        GpgKeyExpirationResult.Error(GpgKeyExpirationError.InternalFailure)
    }

    private fun GpgKeyExpirationRequest.validationError(
        now: () -> Instant,
    ): GpgKeyExpirationError? {
        if (key.privateKeyArmored.isBlank()) {
            return GpgKeyExpirationError.EmptyPrivateKey
        }
        if (change.componentFingerprints.isEmpty()) {
            return GpgKeyExpirationError.NoComponentsSelected
        }
        val expiration = change.expiresAt ?: return null
        return if (
            expiration > GPG_KEY_EXPIRATION_MAX_INSTANT ||
            expiration <= now()
        ) {
            GpgKeyExpirationError.InvalidExpiration
        } else {
            null
        }
    }

    private fun <T> GpgKeyExpirationRequest.withNativeInput(
        referenceTimeEpochSeconds: Long,
        block: (NativeExpirationUpdateInput) -> T,
    ): T {
        val ownedBuffers = mutableListOf<ByteArray>()
        return try {
            val privateKey = key.privateKeyArmored.encodeAndTrack(ownedBuffers)
            val publicKey = key.publicKeyArmored.encodeAndTrack(ownedBuffers)
            candidateRevocationKeys.withEncodedRevocationKeyCandidates(
                targetPrivateKeys = listOf(
                    EncodedRevocationTarget(privateKey, key.fingerprint.normalizeGpgFingerprint()),
                ),
                targetPublicKeys = listOf(
                    EncodedRevocationTarget(publicKey, key.fingerprint.normalizeGpgFingerprint()),
                ),
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            ) { encodedCandidates, _ ->
                block(
                    NativeExpirationUpdateInput(
                        privateKey = privateKey,
                        publicKey = publicKey,
                        expectedPrimaryFingerprint = key.fingerprint.normalizeGpgFingerprint(),
                        componentFingerprints = change.componentFingerprints.map { fingerprint ->
                            fingerprint.normalizeGpgFingerprint()
                        },
                        expiresAtEpochSeconds = change.expiresAt?.epochSeconds,
                        candidateRevocationKeys = encodedCandidates,
                    ),
                )
            }
        } finally {
            ownedBuffers.eraseAll()
        }
    }

    private fun NativeOpenPgpExpirationUpdateResult.toDomain(): GpgKeyExpirationResult =
        when (this) {
            is NativeOpenPgpExpirationUpdateResult.Success -> {
                val material = keyMaterial
                try {
                    GpgKeyExpirationResult.Success(
                        key = GpgKeyMaterial(
                            privateKeyArmored = material.privateKeyArmored.decodeToString(
                                throwOnInvalidSequence = true,
                            ),
                            publicKeyArmored = material.publicKeyArmored.decodeToString(
                                throwOnInvalidSequence = true,
                            ),
                            fingerprint = material.fingerprint,
                            metadata = certificateIndex.toDomain(),
                        ),
                    )
                } finally {
                    material.privateKeyArmored.fill(0)
                    material.publicKeyArmored.fill(0)
                }
            }

            is NativeOpenPgpExpirationUpdateResult.Error ->
                GpgKeyExpirationResult.Error(reason.toDomain())
        }

    private fun String.encodeAndTrack(
        ownedBuffers: MutableList<ByteArray>,
    ): ByteArray = encodeToByteArray().also { buffer -> ownedBuffers += buffer }

    private class NativeExpirationUpdateInput(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val expectedPrimaryFingerprint: String,
        val componentFingerprints: List<String>,
        val expiresAtEpochSeconds: Long?,
        val candidateRevocationKeys: List<ByteArray>,
    )
}

object NativeGpgAgentCrypto : GpgAgentCrypto {
    override fun signHash(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        hashAlgorithm: String,
        hash: ByteArray,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentMessages.SignHashResponse {
        val privateKey = privateKeyArmored.encodeToByteArray()
        return try {
            candidateRevocationKeys.withEncodedRevocationKeyCandidates(
                targetPrivateKeys = listOf(
                    EncodedRevocationTarget(
                        privateKey,
                        metadataKey.fingerprint.normalizeGpgFingerprint(),
                    ),
                ),
            ) { encodedCandidates, _ ->
                when (
                    val result = NativeCrypto.openPgp.agentSignHash(
                        privateKey = privateKey,
                        preferredFingerprint = metadataKey.fingerprint
                            .takeIf { it.isNotBlank() }
                            ?.normalizeGpgFingerprint()
                            .orEmpty(),
                        hashAlgorithm = hashAlgorithm,
                        hash = hash,
                        candidateRevocationKeys = encodedCandidates,
                    )
                ) {
                    is NativeOpenPgpAgentSignResult.Success -> {
                        try {
                            GpgAgentMessages.SignHashResponse(
                                sexp = result.canonicalSexp.toAdvancedSignatureSexp(),
                            )
                        } finally {
                            result.canonicalSexp.fill(0)
                        }
                    }

                    is NativeOpenPgpAgentSignResult.Error -> throw result.reason.toException()
                }
            }
        } finally {
            privateKey.fill(0)
        }
    }

    override fun pkdecrypt(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): GpgAgentMessages.PkdecryptResponse {
        val privateKey = privateKeyArmored.encodeToByteArray()
        return try {
            when (
                val result = NativeCrypto.openPgp.agentDecrypt(
                    privateKey = privateKey,
                    preferredFingerprint = metadataKey.fingerprint
                        .takeIf { it.isNotBlank() }
                        ?.normalizeGpgFingerprint()
                        .orEmpty(),
                    ciphertext = ciphertext,
                    unwrapEcdh = unwrapEcdh,
                )
            ) {
                is NativeOpenPgpAgentDecryptResult.Success -> {
                    try {
                        GpgAgentMessages.PkdecryptResponse(
                            valueSexp = result.canonicalSexp.toAdvancedValueSexp(),
                        )
                    } finally {
                        result.canonicalSexp.fill(0)
                    }
                }

                is NativeOpenPgpAgentDecryptResult.Error -> throw result.reason.toException()
            }
        } finally {
            privateKey.fill(0)
        }
    }
}

private fun NativeOpenPgpExpirationUpdateError.toDomain(): GpgKeyExpirationError = when (this) {
    NativeOpenPgpExpirationUpdateError.EMPTY_PRIVATE_KEY -> GpgKeyExpirationError.EmptyPrivateKey
    NativeOpenPgpExpirationUpdateError.MALFORMED_KEY -> GpgKeyExpirationError.MalformedKey
    NativeOpenPgpExpirationUpdateError.FINGERPRINT_MISMATCH ->
        GpgKeyExpirationError.FingerprintMismatch

    NativeOpenPgpExpirationUpdateError.NO_COMPONENTS_SELECTED ->
        GpgKeyExpirationError.NoComponentsSelected

    NativeOpenPgpExpirationUpdateError.COMPONENT_NOT_FOUND ->
        GpgKeyExpirationError.ComponentNotFound

    NativeOpenPgpExpirationUpdateError.REVOKED_COMPONENT -> GpgKeyExpirationError.RevokedComponent
    NativeOpenPgpExpirationUpdateError.UNRESOLVED_REVOCATION_AUTHORITY ->
        GpgKeyExpirationError.UnresolvedRevocationAuthority

    NativeOpenPgpExpirationUpdateError.UNSUPPORTED_KEY_VERSION ->
        GpgKeyExpirationError.UnsupportedKeyVersion

    NativeOpenPgpExpirationUpdateError.MISSING_SECRET_KEY -> GpgKeyExpirationError.MissingSecretKey
    NativeOpenPgpExpirationUpdateError.PROTECTED_SECRET_KEY ->
        GpgKeyExpirationError.ProtectedSecretKey

    NativeOpenPgpExpirationUpdateError.MISSING_SELF_SIGNATURE ->
        GpgKeyExpirationError.MissingSelfSignature

    NativeOpenPgpExpirationUpdateError.INVALID_EXPIRATION -> GpgKeyExpirationError.InvalidExpiration
    NativeOpenPgpExpirationUpdateError.TIME_CONFLICT -> GpgKeyExpirationError.TimeConflict
    NativeOpenPgpExpirationUpdateError.SIGNATURE_VERIFICATION_FAILED ->
        GpgKeyExpirationError.SignatureVerificationFailed

    NativeOpenPgpExpirationUpdateError.METADATA_RESOLUTION_FAILED ->
        GpgKeyExpirationError.MetadataResolutionFailed

    NativeOpenPgpExpirationUpdateError.INTERNAL_FAILURE -> GpgKeyExpirationError.InternalFailure
    NativeOpenPgpExpirationUpdateError.UNSUPPORTED_SIGNING_HASH ->
        GpgKeyExpirationError.UnsupportedSigningHash
}

private fun NativeOpenPgpAgentError.toException(): Exception = when (this) {
    NativeOpenPgpAgentError.KEY_NOT_FOUND -> GpgAgentKeyNotFoundException()
    NativeOpenPgpAgentError.UNSUPPORTED_ALGORITHM -> GpgAgentUnsupportedAlgorithmException(
        "Unsupported OpenPGP key or operation algorithm",
    )
}

private fun ByteArray.toAdvancedSignatureSexp(): String =
    GpgCanonicalSExpr.useParsed(this) { node ->
        val root = node.requireList("sig-val")
        require(root.items.size == 2 && root.items[0].atomText() == "sig-val") {
            "Native GPG signature is not a sig-val S-expression"
        }
        val algorithm = root.items[1].requireList("signature algorithm")
        val algorithmName = algorithm.items.firstOrNull().atomText()
            ?: throw IllegalArgumentException("Native GPG signature has no algorithm")
        val parameters = algorithm.items.drop(1).associate { parameter ->
            val list = parameter.requireList("signature parameter")
            require(list.items.size == 2) { "Native GPG signature parameter is malformed" }
            val name = list.items[0].atomText()
                ?: throw IllegalArgumentException("Native GPG signature parameter has no name")
            val value = (list.items[1] as? GpgSExpr.Atom)?.bytes
                ?: throw IllegalArgumentException("Native GPG signature parameter has no value")
            name to value
        }
        require(parameters.size == algorithm.items.size - 1) {
            "Native GPG signature contains duplicate parameters"
        }
        when (algorithmName) {
            "rsa" -> {
                require(parameters.keys == setOf("s")) {
                    "Native RSA signature parameters are malformed"
                }
                "(sig-val(rsa(s #${parameters.getValue("s").uppercaseHex()}#)))"
            }

            "ecdsa", "eddsa" -> {
                require(parameters.keys == setOf("r", "s")) {
                    "Native $algorithmName signature parameters are malformed"
                }
                "(sig-val($algorithmName" +
                    "(r #${parameters.getValue("r").uppercaseHex()}#)" +
                    "(s #${parameters.getValue("s").uppercaseHex()}#)))"
            }

            else -> throw GpgAgentUnsupportedAlgorithmException(
                "Unsupported OpenPGP signature algorithm: $algorithmName",
            )
        }
    }

private fun ByteArray.toAdvancedValueSexp(): String =
    GpgCanonicalSExpr.useParsed(this) { node ->
        val root = node.requireList("value")
        require(root.items.size == 2 && root.items[0].atomText() == "value") {
            "Native GPG decryption result is not a value S-expression"
        }
        val value = (root.items[1] as? GpgSExpr.Atom)?.bytes
            ?: throw IllegalArgumentException("Native GPG decryption result has no value")
        "(value #${value.uppercaseHex()}#)"
    }

private fun GpgSExpr.requireList(label: String): GpgSExpr.Listt =
    this as? GpgSExpr.Listt ?: throw IllegalArgumentException("Native GPG $label is not a list")

private fun GpgSExpr?.atomText(): String? = (this as? GpgSExpr.Atom)?.bytes?.let { value ->
    value.decodeToString(throwOnInvalidSequence = true)
}

private fun ByteArray.uppercaseHex(): String = toHex().uppercase()
