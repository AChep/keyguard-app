package com.artemchep.keyguard.common.util.flow

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

inline fun <reified T> List<Flow<T>>.combineToList(
): Flow<ImmutableList<T>> = when (size) {
    0 -> flowOf(persistentListOf())
    1 -> {
        val flow = this.first()
        flow.map { persistentListOf(it) }
    }

    else -> combine(this) { it.toImmutableList() }
}
