package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder

fun BitwardenFolder.toDomain(): DFolder {
    return DFolder(
        id = folderId,
        accountId = accountId,
        revisionDate = revisionDate,
        service = service,
        name = name,
        parentId = parentId,
        hierarchyMode = hierarchyMode,
        deleted = service.deleted,
        synced = !service.deleted &&
                revisionDate == service.remote?.revisionDate,
    )
}
