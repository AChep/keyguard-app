package com.artemchep.keyguard.feature.gpgkey.expiration

import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_DATE
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.resolve
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.feature.gpgkey.GpgKeyExpiryPreset
import com.artemchep.keyguard.feature.gpgkey.gpgKeyExpirationDateRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun defaultGpgKeyExpirationPreset(
    keyInfo: GpgPublicKeyInfo,
): GpgKeyExpiryPreset = if (keyInfo.expiresAt == null) {
    GpgKeyExpiryPreset.Never
} else {
    GpgKeyExpiryPreset.default
}

private fun gpgKeyExpirationsAlign(
    primary: Instant?,
    subKey: Instant?,
): Boolean = when {
    primary == null && subKey == null -> true
    primary == null || subKey == null -> false
    else -> kotlin.math.abs((primary - subKey).inWholeSeconds) <= 3_600L
}

private fun isGpgPrimarySelected(
    keyInfo: GpgPublicKeyInfo,
    componentFingerprints: Set<String>,
): Boolean = componentFingerprints.any { fingerprint ->
    fingerprint.normalizeGpgFingerprint() == keyInfo.fingerprint.normalizeGpgFingerprint()
}

internal fun defaultGpgKeyExpirationComponents(
    keyInfo: GpgPublicKeyInfo,
): Set<String> = buildSet {
    add(keyInfo.fingerprint)
    val subKeys = keyInfo.subKeys.filterNot { it.revoked }
    val allSubKeysAlign = subKeys.all { subKey ->
        gpgKeyExpirationsAlign(
            primary = keyInfo.expiresAt,
            subKey = subKey.expiresAt,
        )
    }
    if (allSubKeysAlign) {
        subKeys.asSequence()
            .filter { it.expiresAt != null }
            .forEach { add(it.fingerprint) }
    }
}

internal enum class GpgKeyExpirationSelectionError {
    RevokedPrimary,
    NoComponents,
    AfterPrimary,
    InvalidExpiration,
}

internal sealed interface GpgKeyExpirationEvaluation {
    data class Valid(
        val change: GpgKeyExpirationChange,
    ) : GpgKeyExpirationEvaluation

    data class Invalid(
        val error: GpgKeyExpirationSelectionError,
    ) : GpgKeyExpirationEvaluation
}

internal fun validateGpgKeyExpirationChange(
    keyInfo: GpgPublicKeyInfo,
    change: GpgKeyExpirationChange,
): GpgKeyExpirationSelectionError? {
    if (keyInfo.revoked) {
        return GpgKeyExpirationSelectionError.RevokedPrimary
    }
    if (change.componentFingerprints.isEmpty()) {
        return GpgKeyExpirationSelectionError.NoComponents
    }
    val primaryExpiresAt = keyInfo.expiresAt
    if (
        !isGpgPrimarySelected(keyInfo, change.componentFingerprints) &&
        primaryExpiresAt != null &&
        change.expiresAt != null &&
        change.expiresAt > primaryExpiresAt
    ) {
        return GpgKeyExpirationSelectionError.AfterPrimary
    }
    return null
}

internal fun evaluateGpgKeyExpirationSelection(
    keyInfo: GpgPublicKeyInfo,
    preset: GpgKeyExpiryPreset,
    componentFingerprints: Set<String>,
    customDate: LocalDate?,
    now: Instant,
    timeZone: TimeZone,
): GpgKeyExpirationEvaluation {
    if (keyInfo.revoked) {
        return GpgKeyExpirationEvaluation.Invalid(
            GpgKeyExpirationSelectionError.RevokedPrimary,
        )
    }
    if (componentFingerprints.isEmpty()) {
        return GpgKeyExpirationEvaluation.Invalid(
            GpgKeyExpirationSelectionError.NoComponents,
        )
    }
    val policy = preset.toPolicy(customDate)
        ?: return GpgKeyExpirationEvaluation.Invalid(
            GpgKeyExpirationSelectionError.InvalidExpiration,
        )
    val selectedExpiry = when (preset) {
        GpgKeyExpiryPreset.Never -> null
        else -> runCatching {
            policy.resolve(
                creationTime = now,
                timeZone = timeZone,
            )
        }.getOrNull()
            ?: return GpgKeyExpirationEvaluation.Invalid(
                GpgKeyExpirationSelectionError.InvalidExpiration,
            )
    }
    if (preset == GpgKeyExpiryPreset.Custom) {
        val selectableDates = gpgKeyExpirationSelectionDateRange(
            keyInfo = keyInfo,
            componentFingerprints = componentFingerprints,
            now = now,
            timeZone = timeZone,
        )
        if (customDate == null || selectableDates == null || customDate !in selectableDates) {
            val error = if (
                !isGpgPrimarySelected(keyInfo, componentFingerprints) &&
                keyInfo.expiresAt != null &&
                selectedExpiry != null &&
                selectedExpiry > keyInfo.expiresAt
            ) {
                GpgKeyExpirationSelectionError.AfterPrimary
            } else {
                GpgKeyExpirationSelectionError.InvalidExpiration
            }
            return GpgKeyExpirationEvaluation.Invalid(error)
        }
    }
    val expiresAt = if (
        preset == GpgKeyExpiryPreset.Custom &&
        selectedExpiry != null &&
        !isGpgPrimarySelected(keyInfo, componentFingerprints)
    ) {
        keyInfo.expiresAt?.let { minOf(selectedExpiry, it) } ?: selectedExpiry
    } else {
        selectedExpiry
    }
    val change = GpgKeyExpirationChange(
        expiresAt = expiresAt,
        componentFingerprints = componentFingerprints,
    )
    return validateGpgKeyExpirationChange(keyInfo, change)
        ?.let(GpgKeyExpirationEvaluation::Invalid)
        ?: GpgKeyExpirationEvaluation.Valid(change)
}

internal fun gpgKeyExpirationSelectionDateRange(
    keyInfo: GpgPublicKeyInfo,
    componentFingerprints: Set<String>,
    now: Instant,
    timeZone: TimeZone,
): ClosedRange<LocalDate>? {
    if (componentFingerprints.isEmpty()) {
        return null
    }
    val currentDate = now.toLocalDateTime(timeZone).date
    val primaryLatestDate = keyInfo.expiresAt
        ?.takeUnless { isGpgPrimarySelected(keyInfo, componentFingerprints) }
        ?.toLocalDateTime(timeZone)
        ?.date
    return gpgKeyExpirationDateRange(
        currentDate = currentDate,
        maximumDate = primaryLatestDate ?: GPG_KEY_EXPIRATION_MAX_DATE,
    )
}
