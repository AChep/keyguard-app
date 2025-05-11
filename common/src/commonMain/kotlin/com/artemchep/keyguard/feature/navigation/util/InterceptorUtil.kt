package com.artemchep.keyguard.feature.navigation.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun <Callback> interceptEvent(
    scope: CoroutineScope,
    interceptorFlow: Flow<Callback?>,
    register: (() -> Callback?) -> (() -> Unit),
): () -> Unit {
    var callback: Callback? = null
    // A registration to remove the back press interceptor
    // from the navigation entry. When it's not null it means
    // that the interceptor is active.
    var unregister: (() -> Unit)? = null

    fun setCallback(
        cb: Callback?,
    ) {
        callback = cb
        if (callback != null) {
            if (unregister != null) {
                // Do nothing
            } else {
                // Register a new back press handler,
                // use the callback holder, so we do
                // not have to re-register in the future.
                unregister = register {
                    callback
                }
            }
        } else {
            unregister?.invoke()
            unregister = null
        }
    }

    val job = scope.launch {
        interceptorFlow
            .map { c ->
                val newCallback = c.takeIf { this.isActive }
                setCallback(newCallback)
            }
            .launchIn(this)

        try {
            awaitCancellation()
        } finally {
            // Unregister the existing interceptor,
            // if there's any.
            unregister?.invoke()
            unregister = null
        }
    }
    return {
        job.cancel()
        // Unregister the existing interceptor,
        // if there's any.
        unregister?.invoke()
        unregister = null
    }
}
