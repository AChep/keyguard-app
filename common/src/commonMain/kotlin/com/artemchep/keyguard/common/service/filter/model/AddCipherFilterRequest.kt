package com.artemchep.keyguard.common.service.filter.model

import com.artemchep.keyguard.common.model.DFilter
import kotlin.time.Clock
import kotlin.time.Instant

data class AddCipherFilterRequest(
    val now: Instant = Clock.System.now(),
    val name: String,
    val filter: Map<String, Set<DFilter.Primitive>>,
)
