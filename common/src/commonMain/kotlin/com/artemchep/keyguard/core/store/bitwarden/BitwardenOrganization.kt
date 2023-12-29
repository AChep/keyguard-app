package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenOrganization(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val organizationId: String,
    val revisionDate: Instant,
    val deletedDate: Instant? = null,
    // service fields
    override val service: BitwardenService,
    // common
    val name: String,
    val avatarColor: String? = null,
    val selfHost: Boolean = false,
    val keyBase64: String = "",
) : BitwardenService.Has<BitwardenOrganization> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)
}
