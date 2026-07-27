package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy

import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta

internal fun recoverPublishedKeePassItem(
    local: LocalItemMeta,
    remote: ServerItemMeta?,
): LocalItemMeta {
    if (local.remoteId != null || remote == null) {
        return local
    }
    return local.copy(
        remoteId = remote.id,
        lastSyncedRevisionDate = remote.revisionDate,
        lastSyncedDeletedDate = remote.deletedDate,
        // When the payload dates still match, decode the published item once
        // to restore the missing service.remote metadata in SQLite.
        requiresLocalRefreshWhenDatesMatch = true,
    )
}
