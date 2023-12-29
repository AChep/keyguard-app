package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * A definition of the distinct application component that
 * makes sense when rendered in a separate window.
 */
@Composable
fun NavigationRouterBackHandler(
    sideEffect: @Composable (BackHandler) -> Unit,
    content: @Composable () -> Unit,
) {
    val handler = remember {
        BackHandler()
    }
    sideEffect(handler)

    CompositionLocalProvider(
        LocalNavigationBackHandler provides handler,
    ) {
        content()
    }
}

class BackHandler(
    val eek: MutableStateFlow<PersistentMap<String, Entry>> = MutableStateFlow(persistentMapOf()),
    val eek2: MutableStateFlow<PersistentMap<String, Entry2>> = MutableStateFlow(persistentMapOf()),
) {
    class Entry(
        val controller: NavigationController,
        val backStack: List<NavigationEntry>,
    )

    class Entry2(
        val onBack: () -> Unit,
        val priority: Int,
    )

    fun register(
        controller: NavigationController,
        backStack: List<NavigationEntry>,
    ): () -> Unit {
        val id = UUID.randomUUID().toString()
        eek.value = eek.value.put(
            key = id,
            value = Entry(
                controller = controller,
                backStack = backStack,
            ),
        )
        return {
            eek.value = eek.value.remove(
                key = id,
            )
        }
    }

    fun register2(
        onBack: () -> Unit,
        priority: Int,
    ): () -> Unit {
        val id = UUID.randomUUID().toString()
        eek2.value = eek2.value.put(
            key = id,
            value = Entry2(
                onBack = onBack,
                priority = priority,
            ),
        )
        return {
            eek2.value = eek2.value.remove(
                key = id,
            )
        }
    }
}

val LocalNavigationBackHandler = compositionLocalOf<BackHandler> {
    throw IllegalStateException()
}
