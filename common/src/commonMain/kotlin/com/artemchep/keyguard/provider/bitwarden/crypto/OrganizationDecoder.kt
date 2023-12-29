package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationEntity
import kotlinx.datetime.Clock

fun BitwardenOrganization.Companion.encrypted(
    accountId: String,
    entity: OrganizationEntity,
) = kotlin.run {
    val organizationRevision = Clock.System.now()
    val service = BitwardenService(
        remote = BitwardenService.Remote(
            id = entity.id,
            revisionDate = organizationRevision,
            deletedDate = null, // can not be trashed
        ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
    BitwardenOrganization(
        accountId = accountId,
        organizationId = entity.id,
        revisionDate = organizationRevision,
        // service fields
        service = service,
        // common
        name = entity.name,
        avatarColor = entity.avatarColor,
        selfHost = entity.selfHost,
        keyBase64 = entity.key,
    )
}
