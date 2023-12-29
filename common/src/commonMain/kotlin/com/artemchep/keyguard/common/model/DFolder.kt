package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.exists
import kotlinx.datetime.Instant

data class DFolder(
    val id: String,
    val accountId: String,
    val revisionDate: Instant,
    val service: BitwardenService,
    val deleted: Boolean,
    val synced: Boolean,
    val name: String,
) : HasAccountId {
    companion object;

    val hasError = service.error.exists(revisionDate)

    override fun accountId(): String = accountId
}
