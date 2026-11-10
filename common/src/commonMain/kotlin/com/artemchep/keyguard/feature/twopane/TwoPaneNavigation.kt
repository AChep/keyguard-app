package com.artemchep.keyguard.feature.twopane

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackGesture
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.ui.screenMaxWidth
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun TwoPaneNavigationContent(
    backStack: PersistentList<NavigationEntry>,
    detailPaneMaxWidth: Dp = screenMaxWidth,
) {
    // Read next to the back stack, so the content of the
    // scaffold gets both of them in the same recomposition.
    // Only a gesture that started on the stack this content
    // renders applies to it.
    val back = LocalNavigationBackGesture.current
        ?.takeIf { it.from.value === backStack }
    TwoPaneScaffold(
        detailPaneMaxWidth = detailPaneMaxWidth,
    ) {
        if (backStack.isEmpty()) {
            // Nothing to draw, the back stack is empty.
            return@TwoPaneScaffold
        }

        val tabletUi = tabletUi

        val masterPane = backStack.first()
        val detailPane = backStack.last()
            .takeIf { it !== masterPane }

        // Back closes the detail pane: the layout animates
        // it, the nodes inside the panes do not.
        val closesDetail = back != null &&
            detailPane != null &&
            !back.to.value.hasDetailPane()
        TwoPaneLayout(
            masterPane = {
                val entries = if (detailPane != null) {
                    // The master pane in tablet UI has no visual depth of
                    // it and should not have the back stack.
                    persistentListOf(masterPane)
                } else {
                    backStack
                }
                NavigationNode(
                    entries = entries,
                    back = null,
                )
            },
            detailPane = detailPane
                ?.run {
                    // composable
                    {
                        // this is a new UI element
                        CompositionLocalProvider(
                            LocalNavigationNodeVisualStack provides persistentListOf(),
                        ) {
                            NavigationNode(
                                entries = backStack,
                                // The root entry of the backstack is shown on a
                                // master pane.
                                offset = if (tabletUi) 1 else 0,
                                back = back.takeUnless { closesDetail },
                            )
                        }
                    }
                },
            detailPaneBack = back.takeIf { closesDetail },
        )
    }
}

/**
 * `true` if the stack has an entry for the detail pane, by the
 * rule [TwoPaneNavigationContent] splits the stack with.
 */
private fun List<NavigationEntry>.hasDetailPane(): Boolean =
    isNotEmpty() && last() !== first()
