package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * The version of the FIDO Credential Exchange Format (CXF) the
 * [document][CxfDocument] conforms to.
 */
@Serializable
data class CxfVersion(
    val major: Int,
    val minor: Int,
) {
    companion object {
        const val CURRENT_MAJOR = 1
        const val CURRENT_MINOR = 0

        val CURRENT = CxfVersion(
            major = CURRENT_MAJOR,
            minor = CURRENT_MINOR,
        )
    }
}
