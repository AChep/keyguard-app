package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

inline fun <T> observerFlow(
    crossinline register: ((T) -> Unit) -> () -> Unit,
): Flow<T> = channelFlow {
    var unregister: (() -> Unit)? = null
    try {
        unregister = withContext(Dispatchers.Main) {
            val setter = { value: T ->
                trySend(value)
            }
            register(setter)
        }
        suspendCancellableCoroutine<Unit> { cont ->
            invokeOnClose {
                cont.resume(Unit)
            }
        }
    } finally {
        withContext(Dispatchers.Main + NonCancellable) {
            // Unregister the broadcast receiver that we have
            // previously registered.
            unregister?.invoke()
        }
    }
}
