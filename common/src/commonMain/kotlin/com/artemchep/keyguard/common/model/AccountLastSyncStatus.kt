package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class AccountLastSyncStatus(
    val date: Instant,
    val error: AccountSyncError?,
)
