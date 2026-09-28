package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import kotlin.time.Instant

/** The production overload requires the caller to decide on a sender address. */
internal fun GpgOpenPgpVerification?.toApiResult() = toApiResult(senderAddress = null)

internal fun verification(
    vararg warnings: GpgOpenPgpVerificationWarning,
    status: GpgOpenPgpVerificationStatus = GpgOpenPgpVerificationStatus.VALID,
    signatures: List<GpgOpenPgpVerification> = emptyList(),
    userIds: List<String> = listOf("Alice <alice@example.com>"),
    confirmedUserIds: List<String> = emptyList(),
) = GpgOpenPgpVerification(
    status = status,
    keyId = "0123456789ABCDEF",
    fingerprint = "00112233445566778899AABB0123456789ABCDEF",
    userIds = userIds,
    createdAt = Instant.fromEpochSeconds(1_700_000_000L),
    warnings = warnings.toList(),
    confirmedUserIds = confirmedUserIds,
    signatures = signatures,
)
