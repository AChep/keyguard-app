package com.artemchep.keyguard.common.service.licensekey.entity

import kotlinx.serialization.Serializable

@Serializable
data class ClaimGoogleRequestEntity(
    val challenge: String,
    val purchaseToken: String,
    val productId: String,
    val productType: String,
)

@Serializable
data class ClaimAppleRequestEntity(
    val challenge: String,
    val signedTransactionInfo: String,
)

@Serializable
data class LicenseStatusRequestEntity(
    val challenge: String,
    val licenseKey: String,
)
