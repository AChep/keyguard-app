package com.artemchep.keyguard.common.service.licensekey.model

import kotlinx.serialization.Serializable

@Serializable
data class LicenseSource(
    val provider: String,
    val productId: String,
    val productType: String,
    val purchaseTokenHash: String,
) {
    val fingerprint: String by lazy {
        listOf(
            provider,
            productType,
            productId,
            purchaseTokenHash,
        ).joinToString(separator = ":")
    }

    companion object {
        const val PROVIDER_GOOGLE_PLAY = "google_play"
    }
}