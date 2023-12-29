package com.artemchep.keyguard.feature.home.vault.search.filter

import com.artemchep.keyguard.common.model.DFilter

@kotlinx.serialization.Serializable
data class FilterHolder(
    val state: Map<String, Set<DFilter.Primitive>>,
) {
    val filter by lazy {
        val f = state
            .map {
                val filters = DFilter.Or(it.value)
                filters
            }
        DFilter.And(f)
    }

    val id: Int = state
        .asSequence()
        .flatMap { it.value }
        .fold(0) { y, x -> y xor x.key.hashCode() }
}
