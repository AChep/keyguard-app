package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

inline fun <reified T> List<Flow<T>>.foldAsList(): Flow<List<T>> =
    if (isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(this) { array -> array.toList() }
    }
