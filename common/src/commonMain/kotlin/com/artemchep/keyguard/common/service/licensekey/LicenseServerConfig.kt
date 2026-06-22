package com.artemchep.keyguard.common.service.licensekey

/**
 * @author Artem Chepurnyi
 */
data class LicenseServerConfig(
    val baseUrl: String,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://license.keyguard.dev"

        val Default = LicenseServerConfig(
            baseUrl = DEFAULT_BASE_URL,
        )
    }
}