package com.artemchep.keyguard.common.service.licensekey.entity

import kotlinx.serialization.Serializable

/**
 * Mirror of the server's
 * `EntitlementResult` model.
 */
@Serializable
data class LicenseEntitlementEntity(
    val licenseId: String? = null,
    val licenseKey: String? = null,
    val licensed: Boolean = false,
    val status: String? = null,
    val tier: String? = null,
    val productKind: String? = null,
    val productId: String? = null,
    val expiresAt: String? = null,
    val checkAfter: String? = null,
    val reason: String? = null,
)