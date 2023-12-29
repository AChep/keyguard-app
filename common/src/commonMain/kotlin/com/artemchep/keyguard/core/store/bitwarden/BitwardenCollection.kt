package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenCollection(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val collectionId: String,
    val externalId: String?,
    val organizationId: String?,
    val revisionDate: Instant,
    val deletedDate: Instant? = null,
    // service fields
    override val service: BitwardenService,
    // common
    val name: String,
    val hidePasswords: Boolean,
    val readOnly: Boolean,
) : BitwardenService.Has<BitwardenCollection> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)
}
