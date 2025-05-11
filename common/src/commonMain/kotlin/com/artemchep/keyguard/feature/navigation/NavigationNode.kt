package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.platform.LocalAnimationFactor
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.kodein.di.compose.rememberInstance

/**
 * A definition of the distinct application component that
 * makes sense when rendered in a separate window.
 */
@Composable
fun NavigationNode(
    entry: NavigationEntry,
) {
    CompositionLocalProvider(
        LocalNavigationNodeLogicalStack provides LocalNavigationNodeLogicalStack.current.add(entry),
    ) {
        NavigationRoute(
            entry = entry,
        )
    }
}

@Immutable
private data class Foo(
    val logicalStack: PersistentList<NavigationEntry>,
    val visualStack: PersistentList<NavigationEntry>,
    val entry: NavigationEntry?,
) {
    companion object {
        val empty = Foo(
            logicalStack = persistentListOf(),
            visualStack = persistentListOf(),
            entry = null,
        )
    }
}

@Composable
fun NavigationNode(
    entries: PersistentList<NavigationEntry>,
    offset: Int = 0,
    modifier: Modifier = Modifier,
) {
    val getNavAnimation by rememberInstance<GetNavAnimation>()

    val newVisualEntries = remember(entries, offset) {
        if (offset < 0 || offset >= entries.size) {
            // If the offset is illegal then we just
            // render empty stack.
            return@remember persistentListOf<NavigationEntry>()
        }
        entries.subList(offset, entries.size)
    }

    val lastFullscreenEntryIndex = newVisualEntries.indexOfLast { it.route !is DialogRoute }

    val logicalStack = LocalNavigationNodeLogicalStack.current
    val visualStack = LocalNavigationNodeVisualStack.current

    Box(
        modifier = modifier,
    ) {
        //
        // Draw screen stack
        //

        val prevFoo = remember {
            mutableStateOf<Foo?>(null)
        }
        val oldFoo = remember {
            mutableStateOf<Foo?>(null)
        }
        val foo = remember(
            logicalStack,
            visualStack,
            entries,
            newVisualEntries,
            lastFullscreenEntryIndex,
        ) {
            // There are no screens in the stack.
            if (lastFullscreenEntryIndex == -1) {
                return@remember Foo.empty
            }

            val newLogicalStack = kotlin.run {
                val items = entries
                    .subList(fromIndex = 0, toIndex = lastFullscreenEntryIndex + 1 + offset)
                logicalStack.addAll(items)
            }
            val newVisualStack = kotlin.run {
                val items = newVisualEntries
                    .subList(fromIndex = 0, toIndex = lastFullscreenEntryIndex + 1)
                visualStack.addAll(items)
            }
            Foo(
                logicalStack = newLogicalStack,
                visualStack = newVisualStack,
                entry = newVisualStack.last(),
            )
        }
        remember(foo) {
            prevFoo.value = oldFoo.value
            oldFoo.value = foo
            1
        }

        val updatedAnimationScale by rememberUpdatedState(LocalAnimationFactor)
        AnimatedContent(
            modifier = Modifier
                .fillMaxSize(),
            targetState = foo,
            transitionSpec = {
                val animationType = getNavAnimation().value
                val transitionType = kotlin.run {
                    if (
                        this.targetState.visualStack.size ==
                        this.initialState.visualStack.size
                    ) {
                        return@run NavigationAnimationType.SWITCH
                    }

                    val isForward = when {
                        initialState.visualStack.size == 0 -> true
                        initialState.visualStack.size < targetState.visualStack.size -> true

                        else -> false
                    }
                    if (isForward) {
                        NavigationAnimationType.GO_FORWARD
                    } else {
                        NavigationAnimationType.GO_BACKWARD
                    }
                }

                NavigationAnimation.transform(
                    scale = updatedAnimationScale,
                    animationType = animationType,
                    transitionType = transitionType,
                )
            },
            label = "",
        ) { foo ->
            val entry = foo.entry
            key(entry?.id) {
                if (entry != null) {
                    CompositionLocalProvider(
                        LocalNavigationNodeLogicalStack provides foo.logicalStack,
                        LocalNavigationNodeVisualStack provides foo.visualStack,
                    ) {
                        NavigationRoute(
                            entry = entry,
                        )
                    }
                }
            }
        }

        //
        // Draw dialog stack
        //

        val bar = remember(
            logicalStack,
            visualStack,
            entries,
            newVisualEntries,
            lastFullscreenEntryIndex,
        ) {
            // There are no screens in the stack.
            if (
                newVisualEntries.isEmpty() ||
                lastFullscreenEntryIndex == newVisualEntries.lastIndex
            ) {
                return@remember Foo.empty
            }

            val newLogicalStack = logicalStack.addAll(entries)
            val newVisualStack = kotlin.run {
                val items = newVisualEntries
                    .subList(
                        fromIndex = lastFullscreenEntryIndex + 1,
                        toIndex = newVisualEntries.size,
                    )
                visualStack.addAll(items)
            }
            Foo(
                logicalStack = newLogicalStack,
                visualStack = newVisualStack,
                entry = entries.last(),
            )
        }
        val transition = updateTransition(bar, "Dialog Transition")
        transition.DialogCrossLifecycle(modifier) { el, alive ->
            val entry = el.entry
            key(entry?.id) {
                if (entry != null) {
                    CompositionLocalProvider(
                        LocalNavigationNodeLogicalStack provides el.logicalStack,
                        LocalNavigationNodeVisualStack provides el.visualStack,
                        LocalNavigationNodeFinishing provides !alive,
                    ) {
                        NavigationRoute(
                            entry = entry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Transition<T>.DialogCrossLifecycle(
    modifier: Modifier = Modifier,
    contentKey: (targetState: T) -> Any? = { it },
    content: @Composable (targetState: T, finishing: Boolean) -> Unit,
) {
    val currentlyVisible = remember { mutableStateListOf<T>().apply { add(currentState) } }
    val contentMap = remember { mutableStateMapOf<T, @Composable () -> Unit>() }
    if (currentState == targetState) {
        // If not animating, just display the current state
        if (currentlyVisible.size != 1 || currentlyVisible[0] != targetState) {
            // Remove all the intermediate items from the list once the animation is finished.
            currentlyVisible.removeAll { it != targetState }
            contentMap.clear()
        }
    }
    if (targetState !in contentMap) {
        // Replace target with the same key if any
        val replacementId =
            currentlyVisible.indexOfFirst { contentKey(it) == contentKey(targetState) }
        if (replacementId == -1) {
            currentlyVisible.add(targetState)
        } else {
            currentlyVisible[replacementId] = targetState
        }
        contentMap.clear()
        currentlyVisible.fastForEach { stateForContent ->
            contentMap[stateForContent] = {
                val alive = this.targetState == stateForContent
                // Spawn unused animation just for the
                // compose to not immediately end the transition.
                animateFloat(transitionSpec = { tween() }) {
                    if (it == stateForContent) 1f else 0f
                }
                Box {
                    content(stateForContent, alive)
                }
            }
        }
    }

    Box(modifier) {
        currentlyVisible.fastForEach { key(contentKey(it)) { contentMap[it]?.invoke() } }
    }
}

@Composable
private fun NavigationRoute(
    entry: NavigationEntry,
) {
    // A composable content must be able to fetch the
    // route instance if it wants to.
    CompositionLocalProvider(
        LocalNavigationEntry provides entry,
        LocalRoute provides entry.route,
    ) {
        key(entry.route) {
            Box(
                modifier = Modifier
                    .testTag("nav:${entry.id}"),
            ) {
                entry.route.Content()
            }
        }
    }
}

internal val LocalNavigationNodeLogicalStack = compositionLocalOf<PersistentList<NavigationEntry>> {
    persistentListOf()
}

internal val LocalNavigationNodeVisualStack = compositionLocalOf<PersistentList<NavigationEntry>> {
    persistentListOf()
}

internal val LocalNavigationNodeFinishing = compositionLocalOf<Boolean> {
    false
}

@Composable
fun VisualStackText() {
    val nav = LocalNavigationNodeVisualStack.current.joinToString { it.id }
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(4.dp),
        text = nav,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}
