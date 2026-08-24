package com.artemchep.keyguard.common.model

import kotlin.time.Instant

/**
 * A single public key as returned by a keyserver search/lookup. Built either
 * from a parsed ASCII-armored key block or from an HKP machine-readable
 * listing.
 */
data class DGpgKeyserverResult(
    val fingerprint: String,
    val keygrip: String? = null,
    val keyId: String? = null,
    val userIds: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val algorithm: String? = null,
    val canSign: Boolean = false,
    val canEncrypt: Boolean = false,
    val createdAt: Instant? = null,
    val expiresAt: Instant? = null,
    val revoked: Boolean = false,
    val subKeys: List<DGpgKeyserverSubKey> = emptyList(),
    /**
     * The ASCII-armored public key, when the full key material is available.
     * HKP `op=index` listings only carry metadata, so this may be `null` until
     * the key is fetched with `op=get`.
     */
    val publicKeyArmored: String? = null,
    /**
     * The keyserver this result originated from, e.g. `https://keys.openpgp.org`.
     */
    val sourceKeyserver: String? = null,
    /**
     * The full keyserver endpoint this result originated from. This should be
     * used for follow-up lookups because [sourceKeyserver] only carries the
     * URL, not the protocol.
     */
    val sourceKeyserverConfig: GpgKeyserverConfig? = null,
)

data class DGpgKeyserverSubKey(
    val fingerprint: String,
    val keygrip: String? = null,
    val keyId: String? = null,
    val algorithm: String? = null,
    val canSign: Boolean = false,
    val canEncrypt: Boolean = false,
    val revoked: Boolean = false,
    val expiresAt: Instant? = null,
)
