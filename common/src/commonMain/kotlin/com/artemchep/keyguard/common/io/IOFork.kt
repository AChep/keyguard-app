package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun <T> IO<T>.dispatchOn(dispatcher: CoroutineDispatcher): IO<T> = {
    withContext(dispatcher) {
        bind()
    }
}

fun <T> IO<T>.mutex(mutex: Mutex): IO<T> = {
    mutex.withLock {
        bind()
    }
}
