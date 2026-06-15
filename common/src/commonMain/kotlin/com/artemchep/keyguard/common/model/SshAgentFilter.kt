package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable

@Serializable
data class SshAgentFilter(
    val state: Map<String, Set<DFilter.Primitive>> = emptyMap(),
) {
    fun normalize(): SshAgentFilter = copy(
        state = state
            .asSequence()
            .map { it.key to it.value }
            .filter { (_, value) -> value.isNotEmpty() }
            .toMap(),
    )

    val isActive: Boolean
        get() = state.values.any { it.isNotEmpty() }

    fun toDFilter(): DFilter = kotlin.run {
        val normalized = normalize().state
        val list = normalized
            .map { (_, filters) ->
                DFilter.Or(filters)
            }
        DFilter.And(list)
    }
}
