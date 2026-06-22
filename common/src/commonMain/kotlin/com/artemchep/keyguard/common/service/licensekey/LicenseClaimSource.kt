package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific stream of purchases that can be exchanged for
 * a portable license key.
 */
interface LicenseClaimSource {
    fun claims(): Flow<RichResult<List<LicenseClaimCandidate>>>
}