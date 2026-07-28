package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import com.artemchep.keyguard.common.model.FolderHierarchyMode
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
    val parentId: String? = null,
    val hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
) : BitwardenService.Has<BitwardenFolder> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)
}
