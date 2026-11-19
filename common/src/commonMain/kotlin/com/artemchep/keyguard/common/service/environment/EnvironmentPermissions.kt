package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.model.isPasswordHidden

internal data class EnvironmentPermissions(val canRead: Boolean, val canWrite: Boolean)

/** Same collection combination as the vault UI, with missing permissions failing closed. */
internal fun environmentPermissions(secret: DSecret, collections: List<DCollection>): EnvironmentPermissions {
    val applicable = collections.filter { it.accountId == secret.accountId && it.id in secret.collectionIds }
    val known = applicable.map { it.id }.toSet().containsAll(secret.collectionIds)
    val hidden = secret.isPasswordHidden(collections)
    val read = known && !hidden
    val write = read && secret.canEdit() && applicable.none { it.readOnly }
    return EnvironmentPermissions(canRead = read, canWrite = write)
}
