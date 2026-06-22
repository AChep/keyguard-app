package com.artemchep.keyguard.common.service.settings.entity

import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.model.LicenseSource
import kotlinx.serialization.Serializable

@Serializable
data class LocalRedeemedLicenseStateEntity(
    val licenseKey: String,
    val snapshot: LicenseEntitlementEntity? = null,
)

@Serializable
data class LocalClaimedLicenseStateEntity(
    val licenseKey: String,
    val snapshot: LicenseEntitlementEntity? = null,
    val source: LicenseSource? = null,
)

@Serializable
data class LocalLicenseClaimFailureEntity(
    val sourceFingerprint: String,
    val failedAt: String,
)
