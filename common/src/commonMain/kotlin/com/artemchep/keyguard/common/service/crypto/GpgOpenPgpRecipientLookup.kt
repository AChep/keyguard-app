package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.util.foundation.crypto.sha256

internal enum class OpenPgpRecipientLookupKind {
    EMAIL,
    KEY_ID,
}

internal enum class OpenPgpRecipientLookupOutcome {
    RESOLVED,
    EMPTY,
    INVALID,
    MISSING,
    AMBIGUOUS,
    NOT_ENCRYPTION_CAPABLE,
}

internal data class OpenPgpRecipientLookupDetail(
    val kind: OpenPgpRecipientLookupKind,
    val reference: String,
    val candidateCount: Int,
    val encryptionCapableCount: Int,
    val outcome: OpenPgpRecipientLookupOutcome,
)

internal data class OpenPgpRecipientResolution<T>(
    val selected: List<T>?,
    val details: List<OpenPgpRecipientLookupDetail>,
) {
    val isAmbiguousOnly: Boolean
        get() = details.isNotEmpty() &&
                details.all {
                    it.outcome == OpenPgpRecipientLookupOutcome.RESOLVED ||
                            it.outcome == OpenPgpRecipientLookupOutcome.AMBIGUOUS
                } &&
                details.any {
                    it.outcome == OpenPgpRecipientLookupOutcome.AMBIGUOUS
                }
}

private const val OPENPGP_KEY_ID_RADIX = 16
private const val OPENPGP_KEY_ID_WIDTH = 16
private const val OPENPGP_SHORT_KEY_ID_WIDTH = 8
private const val OPENPGP_RECIPIENT_REFERENCE_LENGTH = 12

internal fun <T> resolveOpenPgpRecipients(
    userIds: List<String>,
    keyIds: List<Long>,
    candidates: List<T>,
    candidateEmails: (T) -> List<String>,
    candidateKeyIds: (T) -> Set<Long>,
    canEncrypt: (T) -> Boolean,
): OpenPgpRecipientResolution<T> {
    val selected = mutableListOf<T>()
    val details = mutableListOf<OpenPgpRecipientLookupDetail>()
    resolveOpenPgpKeyIdRecipients(
        keyIds = keyIds,
        candidates = candidates,
        candidateKeyIds = candidateKeyIds,
        canEncrypt = canEncrypt,
        selected = selected,
        details = details,
    )
    resolveOpenPgpEmailRecipients(
        userIds = userIds,
        candidates = candidates,
        candidateEmails = candidateEmails,
        canEncrypt = canEncrypt,
        selected = selected,
        details = details,
    )
    if (details.isEmpty()) {
        details += OpenPgpRecipientLookupDetail(
            kind = OpenPgpRecipientLookupKind.EMAIL,
            reference = "none",
            candidateCount = 0,
            encryptionCapableCount = 0,
            outcome = OpenPgpRecipientLookupOutcome.EMPTY,
        )
    }
    val fullyResolved = details.all {
        it.outcome == OpenPgpRecipientLookupOutcome.RESOLVED
    }
    return OpenPgpRecipientResolution(
        selected = selected
            .distinct()
            .takeIf { fullyResolved && it.isNotEmpty() },
        details = details,
    )
}

private fun <T> resolveOpenPgpKeyIdRecipients(
    keyIds: List<Long>,
    candidates: List<T>,
    candidateKeyIds: (T) -> Set<Long>,
    canEncrypt: (T) -> Boolean,
    selected: MutableList<T>,
    details: MutableList<OpenPgpRecipientLookupDetail>,
) {
    keyIds.distinct().forEach { keyId ->
        val matches = candidates.filter { keyId in candidateKeyIds(it) }
        val encryptionCapable = matches.filter(canEncrypt)
        val outcome = when {
            matches.isEmpty() -> OpenPgpRecipientLookupOutcome.MISSING
            matches.size > 1 -> OpenPgpRecipientLookupOutcome.AMBIGUOUS
            encryptionCapable.isEmpty() ->
                OpenPgpRecipientLookupOutcome.NOT_ENCRYPTION_CAPABLE

            else -> OpenPgpRecipientLookupOutcome.RESOLVED
        }
        details += OpenPgpRecipientLookupDetail(
            kind = OpenPgpRecipientLookupKind.KEY_ID,
            reference = keyId
                .toULong()
                .toString(OPENPGP_KEY_ID_RADIX)
                .padStart(OPENPGP_KEY_ID_WIDTH, '0')
                .takeLast(OPENPGP_SHORT_KEY_ID_WIDTH),
            candidateCount = matches.size,
            encryptionCapableCount = encryptionCapable.size,
            outcome = outcome,
        )
        if (outcome == OpenPgpRecipientLookupOutcome.RESOLVED) {
            selected += encryptionCapable.single()
        }
    }
}

private fun <T> resolveOpenPgpEmailRecipients(
    userIds: List<String>,
    candidates: List<T>,
    candidateEmails: (T) -> List<String>,
    canEncrypt: (T) -> Boolean,
    selected: MutableList<T>,
    details: MutableList<OpenPgpRecipientLookupDetail>,
) {
    userIds.forEach { requested ->
        val normalizedEmail = normalizeGpgUserIdEmail(requested)
        if (normalizedEmail == null) {
            details += OpenPgpRecipientLookupDetail(
                kind = OpenPgpRecipientLookupKind.EMAIL,
                reference = openPgpRecipientReference(requested),
                candidateCount = 0,
                encryptionCapableCount = 0,
                outcome = OpenPgpRecipientLookupOutcome.INVALID,
            )
            return@forEach
        }
        val matches = candidates.filter { candidate ->
            val emails = candidateEmails(candidate)
                .mapNotNull(::normalizeGpgUserIdEmail)
            normalizedEmail in emails
        }
        val encryptionCapable = matches.filter(canEncrypt)
        val outcome = when {
            matches.isEmpty() -> OpenPgpRecipientLookupOutcome.MISSING
            encryptionCapable.isEmpty() ->
                OpenPgpRecipientLookupOutcome.NOT_ENCRYPTION_CAPABLE

            encryptionCapable.size > 1 ->
                OpenPgpRecipientLookupOutcome.AMBIGUOUS

            else -> OpenPgpRecipientLookupOutcome.RESOLVED
        }
        details += OpenPgpRecipientLookupDetail(
            kind = OpenPgpRecipientLookupKind.EMAIL,
            reference = openPgpRecipientReference(normalizedEmail),
            candidateCount = matches.size,
            encryptionCapableCount = encryptionCapable.size,
            outcome = outcome,
        )
        if (outcome == OpenPgpRecipientLookupOutcome.RESOLVED) {
            selected += encryptionCapable.single()
        }
    }
}

internal fun openPgpRecipientReference(value: String): String =
    sha256(
        value
            .trim()
            .lowercase()
            .encodeToByteArray(),
    )
        .toHex()
        .take(OPENPGP_RECIPIENT_REFERENCE_LENGTH)

internal fun openPgpRecipientLookupLogMessage(
    requestReference: String,
    detail: OpenPgpRecipientLookupDetail,
): String = buildString {
    append("request=")
    append(requestReference)
    append(" recipient=")
    append(detail.reference)
    append(" kind=")
    append(detail.kind.name.lowercase())
    append(" outcome=")
    append(detail.outcome.name.lowercase())
    append(" candidates=")
    append(detail.candidateCount)
    append(" encryption_capable=")
    append(detail.encryptionCapableCount)
}

internal fun <T> selectedRingsCoverOpenPgpRecipients(
    userIds: List<String>,
    keyIds: List<Long>,
    selected: List<T>,
    candidateEmails: (T) -> List<String>,
    candidateKeyIds: (T) -> Set<Long>,
    canEncrypt: (T) -> Boolean,
): Boolean {
    if (selected.isEmpty()) {
        return false
    }
    val resolution = resolveOpenPgpRecipients(
        userIds = userIds,
        keyIds = keyIds,
        candidates = selected,
        candidateEmails = candidateEmails,
        candidateKeyIds = candidateKeyIds,
        canEncrypt = canEncrypt,
    )
    return resolution.selected != null
}
