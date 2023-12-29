package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

inline fun <reified T> List<Flow<T>>.combineToList(): Flow<List<T>> = if (isEmpty()) {
    flowOf(emptyList())
} else {
    flow {
        val remaining = AtomicInteger(size)
        val list = Array<Any?>(size) { index ->
            null
        }

        if (remaining.get() == 0) {
            emit(list.toList() as List<T>)
            return@flow
        }

        // Combines the flow of models with its position in the
        // list. This is needed to ensure that all of the flows a consumed with
        // the same dispatcher.
        channelFlow<Unit> {
            forEachIndexed { index, flow ->
                launch {
                    var isFirst = true

                    flow.collect { model ->
                        list[index] = model

                        if (isFirst) {
                            isFirst = false
                            remaining.getAndAdd(-1)
                        }

                        // Indicate the update of the list, we don't care if some of the elements
                        // are going to be lost, because we don't pass the data, we pass the update
                        // request.
                        if (remaining.get() == 0) {
                            trySend(Unit)
                        }
                    }
                }
            }

            awaitClose()
        }.collect {
            if (remaining.get() == 0) {
                emit(list.toList() as List<T>)
            }
        }
    }
}
