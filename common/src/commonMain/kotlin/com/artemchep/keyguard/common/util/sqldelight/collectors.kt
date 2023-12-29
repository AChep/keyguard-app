package com.artemchep.keyguard.common.util.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.coroutines.CoroutineContext

fun <T : Any> IO<Query<T>>.flatMapQueryToList(
    context: CoroutineContext,
) = this
    .asFlow()
    .flatMapQueryToList(context)

@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Any> Flow<Query<T>>.flatMapQueryToList(
    context: CoroutineContext,
) = this
    .flatMapLatest { query ->
        query
            .asFlow()
            .mapToList(context)
    }
