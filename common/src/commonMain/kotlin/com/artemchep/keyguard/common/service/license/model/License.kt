package com.artemchep.keyguard.common.service.license.model

data class License(
    val name: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val spdxLicenses: List<SpdxLicense>,
    val scm: Scm? = null,
) {
    data class SpdxLicense(
        val identifier: String,
        val name: String,
        val url: String? = null,
    )

    data class Scm(
        val url: String? = null,
    )
}
