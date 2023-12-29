package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class AccountLastSyncStatus(
    val date: Instant,
    val error: AccountSyncError?,
)
