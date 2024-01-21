package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorHost
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorRegistration
import com.artemchep.keyguard.feature.navigation.state.FlowHolderViewModel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID

interface NavigationEntry : BackPressInterceptorHost {
    companion object {
        fun new() {
        }
    }

    val id: String
    val type: String

    val route: Route
    val scope: CoroutineScope
    val vm: FlowHolderViewModel

    val activeBackPressInterceptorsStateFlow: StateFlow<ImmutableMap<String, BackPressInterceptorRegistration>>

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

    override val vm: FlowHolderViewModel = FlowHolderViewModel(this)

    private val activeBackPressInterceptorsStateSink = MutableStateFlow(
        persistentMapOf<String, BackPressInterceptorRegistration>(),
    )

    override val activeBackPressInterceptorsStateFlow: StateFlow<ImmutableMap<String, BackPressInterceptorRegistration>>
        get() = activeBackPressInterceptorsStateSink

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

    override fun interceptBackPress(
        block: () -> Unit,
    ): () -> Unit {
        val id = UUID.randomUUID().toString()
        val entry = BackPressInterceptorRegistration(
            id = id,
            block = block,
        )
        activeBackPressInterceptorsStateSink.update { state ->
            state.put(id, entry)
        }
        return {
            activeBackPressInterceptorsStateSink.update { state ->
                state.remove(id)
            }
        }
    }

    override fun destroy() {
        vm.destroy()
        job.cancel()
    }
}
