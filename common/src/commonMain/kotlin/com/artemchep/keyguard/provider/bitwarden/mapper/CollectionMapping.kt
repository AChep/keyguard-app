package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection

fun BitwardenCollection.toDomain(): DCollection {
    return DCollection(
        id = collectionId,
        externalId = externalId,
        organizationId = organizationId,
        accountId = accountId,
        revisionDate = revisionDate,
        name = name,
        readOnly = readOnly,
        hidePasswords = hidePasswords,
    )
}
