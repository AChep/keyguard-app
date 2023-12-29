package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import kotlinx.datetime.Clock

fun BitwardenCollection.Companion.encrypted(
    accountId: String,
    entity: CollectionEntity,
) = kotlin.run {
    val collectionRevision = Clock.System.now()
    val service = BitwardenService(
        remote = BitwardenService.Remote(
            id = entity.id,
            revisionDate = collectionRevision,
            deletedDate = null, // can not be trashed
        ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
    BitwardenCollection(
        accountId = accountId,
        collectionId = entity.id,
        externalId = entity.externalId,
        organizationId = entity.organizationId,
        revisionDate = collectionRevision,
        // service fields
        service = service,
        // common
        name = entity.name,
        hidePasswords = entity.hidePasswords,
        readOnly = entity.readOnly,
    )
}
