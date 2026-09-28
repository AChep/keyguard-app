package com.artemchep.keyguard.apple.core

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.main
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.backpress.BackPressInterceptorHost
import com.artemchep.keyguard.feature.navigation.keyboard.KeyEventInterceptorHost
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeImpl
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.leBundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koin.core.Koin

/** Builds a state-flow scope for running shared producers from SwiftUI. */
internal fun Koin.newHeadlessStateFlowScope(
    key: String,
    scope: CoroutineScope,
    navigationInterceptor: ((NavigationIntent) -> Boolean)? = null,
    // Created outside a composition on purpose — only constructing and
    // reading `.value` is needed, neither of which requires a Recomposer.
    // Pass a shared mutable state to let producers that read `colorScheme`
    // (e.g. syntax highlighting) follow the SwiftUI appearance.
    colorSchemeState: State<ColorScheme> = mutableStateOf(lightColorScheme()),
): RememberStateFlowScopeImpl {
    // The shared producers must never run their pipelines on the main thread —
    // the Compose FlowHolderViewModel hands these same producers a
    // Dispatchers.Default screen scope. Override the dispatcher here (keeping
    // the caller's Job, so the lifetime contract is unchanged) so that a
    // wrongly-scoped caller cannot reintroduce a main-thread pipeline.
    @Suppress("NAME_SHADOWING")
    val scope = scope + Dispatchers.Default
    return RememberStateFlowScopeImpl(
        key = key,
        bundle = leBundleOf(),
        showMessage = get(),
        clipboardService = get(),
        clipboardEventBus = get(),
        getScreenState = get(),
        putScreenState = get(),
        windowCoroutineScope = get(),
        navigationController = navigationInterceptor
            ?.let { interceptor -> ForwardingNavigationController(key, interceptor) }
            ?: NoOpNavigationController(key),
        backPressInterceptorHost = NoOpBackPressInterceptorHost,
        keyEventInterceptorHost = NoOpKeyEventInterceptorHost,
        json = get(),
        scope = scope,
        screen = key,
        colorSchemeState = colorSchemeState,
        windowIdState = mutableStateOf(WindowId(0L)),
        screenName = key,
        context = get(),
    )
}

/** Drops every intent of the headless producer [key], reporting it to [DroppedNavigation]. */
@OptIn(DelicateCoroutinesApi::class)
private class NoOpNavigationController(
    private val key: String,
) : NavigationController {
    override val scope: CoroutineScope get() = GlobalScope
    override fun queue(intent: NavigationIntent) = DroppedNavigation.report(key, intent)
    override fun canPop(): Flow<Boolean> = flowOf(false)
}

/**
 * Forwards queued intents to [interceptor]; an intent the interceptor does not
 * claim (returns `false`) is dropped and reported to [DroppedNavigation].
 */
@OptIn(DelicateCoroutinesApi::class)
private class ForwardingNavigationController(
    private val key: String,
    private val interceptor: (NavigationIntent) -> Boolean,
) : NavigationController {
    override val scope: CoroutineScope get() = GlobalScope
    override fun queue(intent: NavigationIntent) {
        if (!interceptor(intent)) {
            DroppedNavigation.report(key, intent)
        }
    }

    override fun canPop(): Flow<Boolean> = flowOf(false)
}

/**
 * The navigation intents of headless producers that no native handler claimed —
 * each one is a control that does nothing on Apple. Logged once per producer and
 * intent type.
 */
internal object DroppedNavigation {
    private val reported = MutableStateFlow(emptySet<String>())

    fun report(key: String, intent: NavigationIntent) {
        val entry = "$key/${intent.describe()}"
        var added = false
        reported.update { set ->
            added = entry !in set
            set + entry
        }
        if (added) {
            println("[Keyguard][nav] dropped $entry (no native handler)")
        }
    }

    private fun NavigationIntent.describe(): String = when (this) {
        is NavigationIntent.NavigateToRoute -> "NavigateToRoute:" + (route::class.simpleName ?: "Route")
        else -> this::class.simpleName ?: "NavigationIntent"
    }
}

private object NoOpBackPressInterceptorHost : BackPressInterceptorHost {
    override fun interceptBackPress(block: () -> Unit): () -> Unit = {}
}

private object NoOpKeyEventInterceptorHost : KeyEventInterceptorHost {
    override fun interceptKeyEvent(block: (KeyEvent) -> Boolean): () -> Unit = {}
}
