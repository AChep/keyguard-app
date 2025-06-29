package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenFolder(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val folderId: String,
    val revisionDate: Instant,
    // service fields
    override val service: BitwardenService,
    // common
    val name: String,
) : BitwardenService.Has<BitwardenFolder> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)
}
