package com.artemchep.keyguard.feature.twopane

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
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
                            )
                        }
                    }
                },
        )
    }
}
