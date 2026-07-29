package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * Top-level FIDO Credential Exchange Format (CXF) v1.0 payload.
 *
 * See the FIDO Alliance Credential Exchange Format specification
 * (cxf-v1.0-ps-errata-20260309).
 */
@Serializable
data class CxfDocument(
    val version: CxfVersion,
    /**
     * The relying-party identifier of the exporter. For the Android GMS
     * transfer flow this is the exporter's package name.
     */
    val exporterRpId: String,
    val exporterDisplayName: String,
    /**
     * The moment the document was created,
     * in Unix epoch seconds.
     */
    val timestamp: Long,
    val accounts: List<CxfAccount>,
)
