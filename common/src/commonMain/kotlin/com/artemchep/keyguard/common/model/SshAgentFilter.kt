package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable

@Serializable
data class SshAgentFilter(
    val state: Map<String, Set<DFilter.Primitive>> = emptyMap(),
) : KeyAgentFilter {
    fun normalize(): SshAgentFilter = copy(
        state = state
            .asSequence()
            .map { it.key to it.value }
            .filter { (_, value) -> value.isNotEmpty() }
            .toMap(),
    )

    override val isActive: Boolean
        get() = state.values.any { it.isNotEmpty() }

    override fun toDFilter(): DFilter = kotlin.run {
        val normalized = normalize().state
        val list = normalized
            .map { (_, filters) ->
                DFilter.Or(filters)
            }
        DFilter.And(list)
    }
}
