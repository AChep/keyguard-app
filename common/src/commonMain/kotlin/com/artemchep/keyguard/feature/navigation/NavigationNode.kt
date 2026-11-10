package com.artemchep.keyguard.feature.navigation

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Renders one root entry and adds it to the logical navigation stack.
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
private data class NodeRenderState(
    val logicalStack: PersistentList<NavigationEntry>,
    val visualStack: PersistentList<NavigationEntry>,
    val entry: NavigationEntry?,
) {
    companion object {
        val empty = NodeRenderState(
            logicalStack = persistentListOf(),
            visualStack = persistentListOf(),
            entry = null,
        )
    }
}

/**
 * The entries of the stack that a node with the [offset] renders.
 */
private fun nodeVisualEntries(
    entries: PersistentList<NavigationEntry>,
    offset: Int,
): List<NavigationEntry> {
    if (offset < 0 || offset >= entries.size) {
        // If the offset is illegal then we just
        // render empty stack.
        return persistentListOf()
    }
    return entries.subList(offset, entries.size)
}

/**
 * The screen layer of a node that renders the [entries]: the last
 * fullscreen entry, or [NodeRenderState.empty] if there is none.
 */
private fun nodeScreenRenderState(
    logicalStack: PersistentList<NavigationEntry>,
    visualStack: PersistentList<NavigationEntry>,
    entries: PersistentList<NavigationEntry>,
    offset: Int,
): NodeRenderState {
    val newVisualEntries = nodeVisualEntries(entries, offset)
    val lastFullscreenEntryIndex = newVisualEntries.indexOfLast { it.route !is DialogRoute }
    // There are no screens in the stack.
    if (lastFullscreenEntryIndex == -1) {
        return NodeRenderState.empty
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
    return NodeRenderState(
        logicalStack = newLogicalStack,
        visualStack = newVisualStack,
        entry = newVisualStack.last(),
    )
}

private fun nodeTransitionType(
    initialState: NodeRenderState,
    targetState: NodeRenderState,
): NavigationAnimationType {
    if (targetState.visualStack.size == initialState.visualStack.size) {
        return NavigationAnimationType.SWITCH
    }

    val isForward = when {
        initialState.visualStack.size == 0 -> true
        initialState.visualStack.size < targetState.visualStack.size -> true

        else -> false
    }
    return if (isForward) {
        NavigationAnimationType.GO_FORWARD
    } else {
        NavigationAnimationType.GO_BACKWARD
    }
}

/**
 * Renders a stack as one active fullscreen route plus optional dialog routes.
 *
 * @param back the Back gesture to preview. The node previews it only if
 *   it renders the stack the gesture started on. A caller that renders
 *   the node inside a subcomposition, for example `BoxWithConstraints`,
 *   reads the gesture where it reads the stack and passes it in, so both
 *   arrive in the same recomposition.
 */
@Composable
fun NavigationNode(
    entries: PersistentList<NavigationEntry>,
    offset: Int = 0,
    modifier: Modifier = Modifier,
    back: NavigationBackGesture? = LocalNavigationBackGesture.current,
) {
    val newVisualEntries = remember(entries, offset) {
        nodeVisualEntries(entries, offset)
    }

    val lastFullscreenEntryIndex = newVisualEntries.indexOfLast { it.route !is DialogRoute }

    val logicalStack = LocalNavigationNodeLogicalStack.current
    val visualStack = LocalNavigationNodeVisualStack.current
    val parentFinishing = LocalNavigationNodeFinishing.current

    Box(
        modifier = modifier,
    ) {
        //
        // Draw screen stack
        //

        val previousRenderState = remember {
            mutableStateOf<NodeRenderState?>(null)
        }
        val oldRenderState = remember {
            mutableStateOf<NodeRenderState?>(null)
        }
        val screenRenderState = remember(
            logicalStack,
            visualStack,
            entries,
            offset,
        ) {
            nodeScreenRenderState(
                logicalStack = logicalStack,
                visualStack = visualStack,
                entries = entries,
                offset = offset,
            )
        }
        remember(screenRenderState) {
            previousRenderState.value = oldRenderState.value
            oldRenderState.value = screenRenderState
            1
        }

        // A node that renders a list derived from the
        // stack of the router ignores its gesture.
        val gesture = back?.takeIf { it.from.value === entries }
        val previewRenderState = remember(
            logicalStack,
            visualStack,
            gesture,
            offset,
        ) {
            gesture?.let {
                nodeScreenRenderState(
                    logicalStack = logicalStack,
                    visualStack = visualStack,
                    entries = it.to.value,
                    offset = offset,
                )
            }
        }

        NavigationAnimatedContent(
            modifier = Modifier
                .fillMaxSize(),
            targetState = screenRenderState,
            back = gesture,
            backState = previewRenderState ?: screenRenderState,
            transitionType = ::nodeTransitionType,
            // By depth: a screen keeps the z-index it entered with,
            // so a constant would go stale.
            zIndex = { it.visualStack.size.toFloat() },
        ) { renderState ->
            val entry = renderState.entry
            key(entry?.id) {
                if (entry != null) {
                    CompositionLocalProvider(
                        LocalNavigationNodeLogicalStack provides renderState.logicalStack,
                        LocalNavigationNodeVisualStack provides renderState.visualStack,
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

        val dialogRenderState = remember(
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
                return@remember NodeRenderState.empty
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
            NodeRenderState(
                logicalStack = newLogicalStack,
                visualStack = newVisualStack,
                entry = entries.last(),
            )
        }
        val transition = updateTransition(dialogRenderState, "Dialog Transition")
        transition.DialogCrossLifecycle(modifier) { el, alive ->
            val entry = el.entry
            key(entry?.id) {
                if (entry != null) {
                    CompositionLocalProvider(
                        LocalNavigationNodeLogicalStack provides el.logicalStack,
                        LocalNavigationNodeVisualStack provides el.visualStack,
                        LocalNavigationNodeFinishing provides (parentFinishing || !alive),
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
    // route get if it wants to.
    CompositionLocalProvider(
        LocalNavigationEntry provides entry,
        LocalRoute provides entry.route,
    ) {
        key(entry.route) {
            val debugModifier = if (N.DEBUG && entry.isDestroyed) {
                val backgroundColor = Color.Red
                Modifier
                    .background(backgroundColor)
                    .padding(top = 24.dp)
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    .testTag("nav:${entry.id}")
                    .then(debugModifier),
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

/**
 * `true` for content that is not the live content of its node: content
 * that is leaving, or that a Back gesture previews. Such content must not
 * take Back or focus, and its dialogs stay collapsed.
 */
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
