package com.artemchep.keyguard.common.service.crypto

/**
 * Resolves the requested key IDs against the vault, requiring every ID
 * to match exactly one ring. Returns null when an ID is missing or
 * ambiguous.
 */
internal fun resolveGpgOpenPgpRequestedRings(
    vault: GpgOpenPgpVault,
    keyIds: List<Long>,
): List<GpgOpenPgpRing>? = resolveUniqueOpenPgpKeyIds(
    keyIds = keyIds,
    candidates = vault.rings,
    candidateKeyIds = GpgOpenPgpRing::allKeyIds,
)

/** Every ring that matches any of the requested key IDs, ambiguity allowed. */
internal fun matchingGpgOpenPgpRequestedRings(
    vault: GpgOpenPgpVault,
    keyIds: List<Long>,
): List<GpgOpenPgpRing> = keyIds
    .flatMap { keyId ->
        vault.rings.filter { keyId in it.allKeyIds }
    }
    .distinct()

/** Outcome of resolving the single signing ring for an operation. */
internal sealed interface GpgOpenPgpSignerSelection {
    data class Resolved(
        val ring: GpgOpenPgpRing,
        val privateKey: GpgOpenPgpPrivateKey?,
    ) : GpgOpenPgpSignerSelection

    /** The explicitly requested signing key is missing or ambiguous. */
    data object RequestedKeyUnavailable : GpgOpenPgpSignerSelection

    /** The selection does not narrow down to one signing-capable key. */
    data object NoSingleSigner : GpgOpenPgpSignerSelection

    /** The signing key carries no private signing material. */
    data object MissingPrivateMaterial : GpgOpenPgpSignerSelection
}

@Suppress("ReturnCount")
internal fun selectGpgOpenPgpSigner(
    vault: GpgOpenPgpVault,
    selectedRings: List<GpgOpenPgpRing>,
    signKeyId: Long?,
    requirePrivateMaterial: Boolean,
): GpgOpenPgpSignerSelection {
    val requested = signKeyId
        ?.let {
            resolveGpgOpenPgpRequestedRings(vault, listOf(it))
                ?: return GpgOpenPgpSignerSelection.RequestedKeyUnavailable
        }
        .orEmpty()
    val ring = selectedRings
        .filter(GpgOpenPgpRing::canSign)
        .filter { requested.isEmpty() || it in requested }
        .singleOrNull()
        ?: return GpgOpenPgpSignerSelection.NoSingleSigner
    val privateKey = ring.privateKey()
    if (requirePrivateMaterial && privateKey == null) {
        return GpgOpenPgpSignerSelection.MissingPrivateMaterial
    }
    return GpgOpenPgpSignerSelection.Resolved(
        ring = ring,
        privateKey = privateKey,
    )
}

/** Outcome of resolving the encryption recipient rings for an operation. */
internal sealed interface GpgOpenPgpRecipientSelection {
    data class Resolved(
        val recipients: List<GpgOpenPgpRing>,
    ) : GpgOpenPgpRecipientSelection

    /** An explicitly requested recipient key is missing or ambiguous. */
    data object RequestedKeyUnavailable : GpgOpenPgpRecipientSelection

    /** No encryption-capable key survives the selection. */
    data object NoEncryptionCapableRecipient : GpgOpenPgpRecipientSelection
}

@Suppress("ReturnCount")
internal fun selectGpgOpenPgpEncryptionRecipients(
    vault: GpgOpenPgpVault,
    selectedRings: List<GpgOpenPgpRing>,
    keyIds: List<Long>,
): GpgOpenPgpRecipientSelection {
    val requested = resolveGpgOpenPgpRequestedRings(
        vault = vault,
        keyIds = keyIds,
    ) ?: return GpgOpenPgpRecipientSelection.RequestedKeyUnavailable
    val recipients = selectedRings
        .filter(GpgOpenPgpRing::canEncrypt)
        .filter { requested.isEmpty() || it in requested }
        .distinct()
    if (recipients.isEmpty()) {
        return GpgOpenPgpRecipientSelection.NoEncryptionCapableRecipient
    }
    return GpgOpenPgpRecipientSelection.Resolved(
        recipients = recipients,
    )
}

/** Outcome of resolving the single exportable ring for an operation. */
internal sealed interface GpgOpenPgpExportSelection {
    data class Resolved(
        val ring: GpgOpenPgpRing,
    ) : GpgOpenPgpExportSelection

    /** The requested key is missing, ambiguous, or not selected. */
    data object RequestedKeyUnavailable : GpgOpenPgpExportSelection
}

@Suppress("ReturnCount")
internal fun selectGpgOpenPgpExportKey(
    vault: GpgOpenPgpVault,
    selectedRings: List<GpgOpenPgpRing>,
    keyId: Long?,
): GpgOpenPgpExportSelection {
    val ring = keyId
        ?.let {
            resolveGpgOpenPgpRequestedRings(vault, listOf(it))
                ?: return GpgOpenPgpExportSelection.RequestedKeyUnavailable
        }
        .orEmpty()
        .filter { it in selectedRings }
        .singleOrNull()
        ?: return GpgOpenPgpExportSelection.RequestedKeyUnavailable
    return GpgOpenPgpExportSelection.Resolved(
        ring = ring,
    )
}

/**
 * The rings an operation may act on without user interaction, or null
 * when the request does not identify an unambiguous selection.
 */
@Suppress("ReturnCount")
internal fun resolveGpgOpenPgpAutomaticSelection(
    kind: GpgOpenPgpOperationKind,
    vault: GpgOpenPgpVault,
    signKeyId: Long?,
    exportKeyId: Long?,
    recipientResolution: OpenPgpRecipientResolution<GpgOpenPgpRing>?,
): List<GpgOpenPgpRing>? {
    fun resolveKeyIds(keyIds: List<Long>): List<GpgOpenPgpRing>? =
        resolveGpgOpenPgpRequestedRings(vault, keyIds)

    fun resolveSigner(): GpgOpenPgpRing? {
        signKeyId
            ?: return null
        return resolveKeyIds(listOf(signKeyId))
            ?.filter(GpgOpenPgpRing::canSign)
            ?.singleOrNull()
    }

    fun resolveRecipients(): List<GpgOpenPgpRing>? =
        recipientResolution?.selected

    return when (kind) {
        GpgOpenPgpOperationKind.GET_KEY -> exportKeyId
            ?.let { resolveKeyIds(listOf(it)) }
            ?.filter(GpgOpenPgpRing::canExport)
            ?.takeIf { it.size == 1 }

        GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
        GpgOpenPgpOperationKind.CLEAR_SIGN,
        GpgOpenPgpOperationKind.DETACHED_SIGN,
        -> listOfNotNull(resolveSigner())
            .takeIf { it.size == 1 }

        GpgOpenPgpOperationKind.GET_KEY_IDS,
        GpgOpenPgpOperationKind.ENCRYPT,
        -> resolveRecipients()

        GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT -> {
            val recipients = resolveRecipients()
                ?: return null
            val signer = resolveSigner()
                ?: return null
            (recipients + signer).distinct()
        }

        GpgOpenPgpOperationKind.DECRYPT_VERIFY,
        GpgOpenPgpOperationKind.DECRYPT_METADATA,
        -> vault.rings

        else -> null
    }
}

/**
 * The rings offered for interactive approval: the explicitly requested
 * keys and certified e-mail matches when present, the whole vault
 * otherwise, plus preferred key hints and narrowed down to the rings
 * capable of the operation. Preferred keys augment the chooser without
 * narrowing it.
 */
internal fun gpgOpenPgpApprovalCandidates(
    kind: GpgOpenPgpOperationKind,
    vault: GpgOpenPgpVault,
    requestedEmails: List<String>,
    keyIds: List<Long>,
    preferredKeyIds: List<Long> = emptyList(),
): List<GpgOpenPgpRing> {
    val explicitlyRequested = matchingGpgOpenPgpRequestedRings(
        vault = vault,
        keyIds = keyIds,
    )
    val preferred = matchingGpgOpenPgpRequestedRings(
        vault = vault,
        keyIds = preferredKeyIds,
    )
    val emailMatches = requestedEmails
        .flatMap { requested ->
            val email = normalizeGpgMailboxAddress(requested)
                ?: return@flatMap emptyList()
            vault.rings.filter { ring ->
                email in ring.info.emails.mapNotNull(::normalizeGpgMailboxAddress)
            }
        }
    val narrowed = (explicitlyRequested + emailMatches).distinct()
    val base = narrowed.ifEmpty { vault.rings }
    return (base + preferred).distinct().filter { ring ->
        when (kind) {
            GpgOpenPgpOperationKind.CLEAR_SIGN,
            GpgOpenPgpOperationKind.DETACHED_SIGN,
            GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
            -> ring.canSign

            GpgOpenPgpOperationKind.ENCRYPT,
            GpgOpenPgpOperationKind.GET_KEY_IDS,
            GpgOpenPgpOperationKind.AUTOCRYPT_STATUS,
            -> ring.canEncrypt

            GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT -> ring.canSign || ring.canEncrypt

            GpgOpenPgpOperationKind.DECRYPT_VERIFY,
            GpgOpenPgpOperationKind.DECRYPT_METADATA,
            -> ring.canDecrypt || ring.info.publicKeyArmored.isNotBlank()

            GpgOpenPgpOperationKind.GET_KEY -> ring.canExport
            else -> false
        }
    }
}
