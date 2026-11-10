package com.artemchep.keyguard.feature.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The thread a chain of navigation controllers handles its intents on.
 */
internal interface NavigationExecutor {
    /**
     * `true` if the calling thread may handle an intent right now.
     */
    val isCurrent: Boolean

    /**
     * Runs the [task] later on the executor's thread. The task is dropped
     * if the [scope] is cancelled before it runs. Tasks posted from one
     * thread run in the order they were posted.
     */
    fun post(scope: CoroutineScope, task: () -> Unit)
}

/**
 * Handles navigation on the main thread.
 */
internal object MainNavigationExecutor : NavigationExecutor {
    override val isCurrent: Boolean
        get() = !Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext)

    override fun post(scope: CoroutineScope, task: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            task()
        }
    }
}
