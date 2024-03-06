package com.artemchep.keyguard.common.service.filter.entity

import com.artemchep.keyguard.common.model.DFilter
import kotlinx.serialization.Serializable

@Serializable
data class FilterEntity(
    val state: Map<String, Set<DFilter.Primitive>>,
)
