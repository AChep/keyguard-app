package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity

fun BitwardenFolder.Companion.encrypted(
    accountId: String,
    folderId: String,
    entity: FolderEntity,
) = kotlin.run {
    val service = BitwardenService(
        remote = BitwardenService.Remote(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = null, // can not be trashed
        ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
    BitwardenFolder(
        accountId = accountId,
        folderId = folderId,
        revisionDate = entity.revisionDate,
        // service fields
        service = service,
        // common
        name = entity.name,
    )
}
