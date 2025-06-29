package com.artemchep.keyguard.common.service.hibp.breaches.all.model

import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class LocalBreachesEntity(
    val updatedAt: Instant,
    val model: HibpBreachGroup,
)
