package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import kotlin.time.Instant

data class GpgUserIdReplacementRequest(
    val key: GpgKeyMaterial,
    /** Stable ID derived from the exact old User ID packet body. */
    val oldIdentityId: String,
    /** Exact non-blank UTF-8 User ID packet value; no normalization is applied. */
    val newUserId: String,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
)

sealed interface GpgUserIdReplacementResult {
    data class Success(
        val key: GpgKeyMaterial,
        /** Empty when unchanged or when the applied mutation is local-only. */
        val replacementCertificateArmored: String,
        /** A local-only change is true even though no transferable certificate is returned. */
        val changed: Boolean,
        val effectiveAt: Instant,
        val oldIdentityId: String,
        val newIdentityId: String,
        val primaryUserId: String,
    ) : GpgUserIdReplacementResult

    data class Error(
        val reason: GpgUserIdMutationError,
        val retryAt: kotlin.time.Instant? = null,
    ) : GpgUserIdReplacementResult
}
