package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable

/**
 * User-configurable keyserver settings. Defaults to keys.openpgp.org (the
 * verifying, privacy-respecting server) using its VKS JSON API.
 */
@Serializable
data class GpgKeyserverConfig(
    val url: String = DEFAULT_URL,
    val protocol: Protocol = Protocol.VKS,
) {
    enum class Protocol {
        /** keys.openpgp.org VKS JSON API. */
        VKS,

        /** Classic HTTP Keyserver Protocol (e.g. keyserver.ubuntu.com). */
        HKP,
    }

    companion object {
        const val DEFAULT_URL = "https://keys.openpgp.org"
        const val HKP_UBUNTU_URL = "https://keyserver.ubuntu.com"
    }
}
