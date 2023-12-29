package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta

fun BitwardenMeta.toDomain(): DMeta = DMeta(
    accountId = AccountId(accountId),
    lastSyncTimestamp = lastSyncTimestamp,
    lastSyncResult = lastSyncResult?.toDomain(),
)

private fun BitwardenMeta.LastSyncResult.toDomain(): DMeta.LastSyncResult = when (this) {
    is BitwardenMeta.LastSyncResult.Failure ->
        DMeta.LastSyncResult.Failure(
            timestamp = timestamp,
            reason = reason,
            requiresAuthentication = requiresAuthentication,
        )

    is BitwardenMeta.LastSyncResult.Success -> DMeta.LastSyncResult.Success
}
