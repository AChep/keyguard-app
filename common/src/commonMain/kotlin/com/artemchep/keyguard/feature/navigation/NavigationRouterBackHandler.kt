package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.Uuid

/**
 * A definition of the distinct application component that
 * makes sense when rendered in a separate window.
 */
@Composable
fun NavigationRouterBackHandler(
    handler: BackHandler,
    content: @Composable () -> Unit,
) {
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
        val canPop: () -> Boolean,
    )

    class Entry2(
        val onBack: () -> Unit,
        val priority: Int,
    )

    fun register(
        controller: NavigationController,
        backStack: List<NavigationEntry>,
        // A live check that this router can handle Back locally. Platform/root
        // registrations leave it false so Escape cannot reach application exit.
        canPop: () -> Boolean = { false },
    ): () -> Unit {
        val id = Uuid.random().toString()
        eek.value = eek.value.put(
            key = id,
            value = Entry(
                controller = controller,
                backStack = backStack,
                canPop = canPop,
            ),
        )
        return {
            eek.value = eek.value.remove(
                key = id,
            )
        }
    }

    /** Handles unconsumed Escape events after the window's regular shortcuts. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isEscape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        val hasModifier =
            event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed
        if (!isEscape || hasModifier) {
            return false
        }

        // Read the live local stacks, not an asynchronously collected canPop flow:
        // another Escape may arrive before recomposition after the last route closes.
        val target = eek.value.values
            .filter { it.canPop() }
            .maxByOrNull { it.backStack.size }
        target?.controller?.queue(NavigationIntent.Pop)
        return target != null
    }

    fun register2(
        onBack: () -> Unit,
        priority: Int,
    ): () -> Unit {
        val id = Uuid.random().toString()
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
    val msg = "LocalNavigationBackHandler is not provided."
    throw IllegalStateException(msg)
}
