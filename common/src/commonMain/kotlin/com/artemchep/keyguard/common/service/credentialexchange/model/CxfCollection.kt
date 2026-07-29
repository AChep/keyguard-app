package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * A grouping of items within an [account][CxfAccount], mapped from a Keyguard
 * folder. Collections may nest via [subCollections].
 */
@Serializable
data class CxfCollection(
    // base64url-encoded identifier
    val id: String,
    /**
     * The moment the collection was created,
     * in Unix epoch seconds.
     */
    val creationAt: Long? = null,
    /**
     * The moment the collection was last modified,
     * in Unix epoch seconds.
     */
    val modifiedAt: Long? = null,
    val title: String,
    val subtitle: String? = null,
    /**
     * CXF v1.0 declares `items` as a required array, and §2.1.2 requires it to
     * be present in the payload even when empty. Deliberately no default
     * value.
     */
    val items: List<CxfLinkedItem>,
    val subCollections: List<CxfCollection>? = null,
)

/**
 * A reference from a [collection][CxfCollection] to an [item][CxfItem] that
 * belongs to it.
 */
@Serializable
data class CxfLinkedItem(
    /**
     * The base64url-encoded id of the referenced [CxfItem].
     */
    val item: String,
    /**
     * The base64url-encoded id of the account owning the item, when it differs
     * from the enclosing account.
     */
    val account: String? = null,
)
