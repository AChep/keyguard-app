package com.artemchep.keyguard.feature.send.search.filter

import com.artemchep.keyguard.common.model.DSendFilter

@kotlinx.serialization.Serializable
data class FilterSendHolder(
    val state: Map<String, Set<DSendFilter.Primitive>>,
) {
    val filter by lazy {
        val f = state
            .map {
                val filters = DSendFilter.Or(it.value)
                filters
            }
        DSendFilter.And(f)
    }

    val id: Int = state
        .asSequence()
        .flatMap { it.value }
        .fold(0) { y, x -> y xor x.key.hashCode() }
}
