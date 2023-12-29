package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class PersistedSession(
    val masterKey: MasterKey,
    val createdAt: Instant,
    val persistedAt: Instant,
)
