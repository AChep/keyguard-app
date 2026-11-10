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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlin.uuid.Uuid

/**
 * Runtime instance of a route in a stack.
 *
 * Entries own route-scoped state, coroutine lifetime, child routers, and local
 * back/key interceptors. Removing an entry destroys all of that owned state.
 */
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

    /**
     * Returns `true` if navigation entry is destroyed. If so, it must not be used afterwards,
     * as it will emit no data to the frontend user interface.
     */
    val isDestroyed: Boolean

    val activeBackPressInterceptorsStateFlow: StateFlow<ImmutableMap<String, BackPressInterceptorRegistration>>

    val activeKeyEventInterceptorsStateFlow: StateFlow<ImmutableMap<String, KeyEventInterceptorRegistration>>

    val subStacks: Map<String, NavigationPile>

    fun getOrCreate(
        id: String,
        create: () -> NavigationPile,
    ): NavigationPile

    /**
     * Destroys the entry. Calling it again does nothing.
     */
    fun destroy()
}

val LocalNavigationEntry = staticCompositionLocalOf<NavigationEntry> {
    throw IllegalStateException("Home layout must be initialized!")
}

/**
 * Default route entry implementation used by routers and root nodes.
 */
class NavigationEntryImpl internal constructor(
    private val source: String,
    parent: CoroutineScope,
    /**
     * The configuration this entry was created for. A router
     * keeps the entry for as long as it keeps the configuration.
     */
    internal val config: NavigationEntryConfig,
) : NavigationEntry {
    constructor(
        source: String,
        parent: CoroutineScope,
        id: String,
        route: Route,
    ) : this(
        source = source,
        parent = parent,
        config = NavigationEntryConfig(
            id = id,
            route = route,
        ),
    )

    override val id: String get() = config.id

    override val route: Route get() = config.route

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

    override val isDestroyed get() = job.isCompleted || job.isCancelled

    init {
        require('/' !in id)
        require('/' !in type)
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

    override val subStacks = mutableMapOf<String, NavigationPile>()

    override fun getOrCreate(
        id: String,
        create: () -> NavigationPile,
    ): NavigationPile = subStacks.getOrPut(id, create)

    override fun destroy() {
        vm.destroy()
        job.cancel()
    }

    override fun toString(): String = "NavigationEntryImpl(id=$id, route=$route, source=$source)"
}
