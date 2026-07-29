package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * A single account/profile contained in a CXF [document][CxfDocument].
 */
@Serializable
data class CxfAccount(
    // base64url-encoded identifier
    val id: String,
    val username: String,
    val email: String,
    val fullName: String? = null,
    /**
     * CXF v1.0 declares `collections` as a required array, and §2.1.2 requires
     * it to be present in the payload even when empty. Deliberately no default
     * value.
     */
    val collections: List<CxfCollection>,
    /**
     * CXF v1.0 declares `items` as a required array, and §2.1.2 requires it to
     * be present in the payload even when empty. Deliberately no default value.
     *
     * Export-only: this class is never deserialized — the importer hand-walks
     * the [kotlinx.serialization.json.JsonObject] so that one bad item costs
     * one item.
     */
    val items: List<CxfItem>,
)
