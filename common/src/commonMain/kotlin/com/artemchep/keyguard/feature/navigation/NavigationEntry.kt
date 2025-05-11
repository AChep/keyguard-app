package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.util.job
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorHost
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorRegistration
import com.artemchep.keyguard.feature.navigation.keyboard.KeyEventInterceptorHost
import com.artemchep.keyguard.feature.navigation.keyboard.KeyEventInterceptorRegistration
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
import kotlin.uuid.Uuid

interface NavigationEntry : BackPressInterceptorHost, KeyEventInterceptorHost {
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

    val activeKeyEventInterceptorsStateFlow: StateFlow<ImmutableMap<String, KeyEventInterceptorRegistration>>

    fun getOrCreate(id: String, create: () -> NavigationStack): NavigationPile

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

    private val job = Job(parent = parent.job)

    override val scope = parent + job

    override val vm: FlowHolderViewModel = FlowHolderViewModel(this)

    private val activeBackPressInterceptorsStateSink = MutableStateFlow(
        persistentMapOf<String, BackPressInterceptorRegistration>(),
    )

    override val activeBackPressInterceptorsStateFlow: StateFlow<ImmutableMap<String, BackPressInterceptorRegistration>>
        get() = activeBackPressInterceptorsStateSink

    private val activeKeyEventInterceptorsStateSink = MutableStateFlow(
        persistentMapOf<String, KeyEventInterceptorRegistration>(),
    )

    override val activeKeyEventInterceptorsStateFlow: StateFlow<ImmutableMap<String, KeyEventInterceptorRegistration>>
        get() = activeKeyEventInterceptorsStateSink

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
        val id = Uuid.random().toString()
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

    override fun interceptKeyEvent(
        block: (KeyEvent) -> Boolean,
    ): () -> Unit {
        val id = Uuid.random().toString()
        val entry = KeyEventInterceptorRegistration(
            id = id,
            block = block,
        )
        activeKeyEventInterceptorsStateSink.update { state ->
            state.put(id, entry)
        }
        return {
            activeKeyEventInterceptorsStateSink.update { state ->
                state.remove(id)
            }
        }
    }

    private val subStacks = mutableMapOf<String, NavigationPile>()

    override fun getOrCreate(
        id: String,
        create: () -> NavigationStack,
    ): NavigationPile = subStacks
        .getOrPut(id) {
            val navStack = create()
            NavigationPile(navStack)
        }

    override fun destroy() {
        vm.destroy()
        job.cancel()
    }
}
