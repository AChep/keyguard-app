package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class PersistedSession(
    val masterKey: MasterKey,
    val createdAt: Instant,
    val persistedAt: Instant,
)
