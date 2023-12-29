package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private const val TAG = "Controller"

@Composable
fun NavigationController(
    scope: CoroutineScope? = null,
    canPop: Flow<Boolean>,
    handle: (NavigationIntent) -> NavigationIntent?,
    content: @Composable (NavigationController) -> Unit,
) {
    val finalTag = N.tag(TAG)

    val parent = LocalNavigationController.current
    val controller = remember(canPop, handle, parent) {
        val combinedCanPop = combine(
            canPop,
            parent.canPop(),
        ) { a, b -> a || b }
        val handler = object : NavigationController {
            override val scope: CoroutineScope = kotlin.run {
                scope ?: parent.scope
            }

            override fun queue(intent: NavigationIntent) {
                val newIntent = handle(intent)
                    ?: return
                parent.queue(newIntent)
            }

            override fun canPop(): Flow<Boolean> = combinedCanPop
        }
        handler
    }
    CompositionLocalProvider(
        LocalNavigationController provides controller,
    ) {
        content(controller)
    }
}

interface NavigationController {
    val scope: CoroutineScope

    fun queue(intent: NavigationIntent)

    fun canPop(): Flow<Boolean>
}

internal val LocalNavigationController = staticCompositionLocalOf<NavigationController> {
    object : NavigationController {
        override val scope: CoroutineScope get() = GlobalScope

        override fun queue(intent: NavigationIntent) {
            // Do nothing.
        }

        override fun canPop(): Flow<Boolean> = flowOf(false)
    }
}
