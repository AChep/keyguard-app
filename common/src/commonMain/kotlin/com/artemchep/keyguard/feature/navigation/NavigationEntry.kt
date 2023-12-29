package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.artemchep.keyguard.feature.navigation.state.FlowHolderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

interface NavigationEntry {
    companion object {
        fun new() {
        }
    }

    val id: String
    val type: String

    val route: Route
    val scope: CoroutineScope
    val vm: FlowHolderViewModel

    fun destroy()
}

internal val LocalNavigationEntry = staticCompositionLocalOf<NavigationEntry> {
    throw IllegalStateException("Home layout must be initialized!")
}

data class NavigationEntryImpl(
    private val source: String,
    private val parent: CoroutineScope,
    override val id: String,
    override val route: Route,
) : NavigationEntry {
    override val type: String = route::class.simpleName.orEmpty()

    private val job = Job()

    override val scope = parent + job

    override val vm: FlowHolderViewModel = FlowHolderViewModel(source, scope)

    init {
        require('/' !in id)
        require('/' !in type)

        parent.launch {
            try {
                suspendCancellableCoroutine<Unit> { }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    destroy()
                }
            }
        }
    }

    override fun destroy() {
        vm.destroy()
        job.cancel()
    }
}
