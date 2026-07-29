package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * A single vault item exported to the CXF format. Carries one or more
 * [credentials][CxfCredential].
 */
@Serializable
data class CxfItem(
    // base64url-encoded identifier
    val id: String,
    /**
     * The moment the item was created,
     * in Unix epoch seconds.
     */
    val creationAt: Long? = null,
    /**
     * The moment the item was last modified,
     * in Unix epoch seconds.
     */
    val modifiedAt: Long? = null,
    val title: String,
    val subtitle: String? = null,
    val favorite: Boolean? = null,
    val scope: CxfCredentialScope? = null,
    val credentials: List<CxfCredential>,
    val tags: List<String>? = null,
)
