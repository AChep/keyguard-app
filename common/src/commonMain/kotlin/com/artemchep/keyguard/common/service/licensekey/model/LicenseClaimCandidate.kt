package com.artemchep.keyguard.common.service.licensekey.model

data class LicenseClaimCandidate(
    val claim: LicenseClaim,
    val source: LicenseSource,
    val priority: Int = 0, // lower is higher priority
)

fun Iterable<LicenseClaimCandidate>.selectBestLicenseClaimCandidate(
): LicenseClaimCandidate? = run {
    val comparator = compareBy<LicenseClaimCandidate>(
        { it.priority },
        { it.source.fingerprint },
    )
    minWithOrNull(comparator)
}
