@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keyboard focus of a lazy list with [rememberListFocus]. */
class ListFocusSceneTest {
    // Internal, so the scene helpers below the class can reach it.
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun upFromTheTopRowFocusesTheExitUpTarget() {
        withList(ListHost()) {
            // The list focuses its top row.
            assertEquals(listOf("Sub A"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Sub B"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Row 1"), focusedTexts())

            // Up inside the list still moves between the rows.
            press(Key.DirectionUp)
            assertEquals(listOf("Sub B"), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf("Sub A"), focusedTexts())

            // The search alone finds Filter, right above the list.
            press(Key.DirectionUp)
            assertEquals(listOf("Field"), focusedTexts())
        }
    }

    @Test
    fun upFromTheTopRowGoesWhereTheSearchFindsIfTheExitUpTargetCannotFocus() {
        withList(ListHost(exitUpEnabled = false)) {
            assertEquals(listOf("Sub A"), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf("Filter"), focusedTexts())
        }
    }

    @Test
    fun onlyTheComposedTargetsAreKept() {
        val host = ListHost(focusFirstOnEnter = false)
        withList(host) {
            val focus = assertNotNull(host.focus)
            assertTrue(focus.targetCount in 1 until ROW_COUNT)

            // Each step composes rows that the old ones made way for.
            for (index in 0..ROW_COUNT step SCROLL_STEP) {
                host.listState.requestScrollToItem(index)
                draw()
            }
            val composed = host.listState.layoutInfo.visibleItemsInfo.size
            assertTrue(
                focus.targetCount <= composed + TARGET_COUNT_SLACK,
                "${focus.targetCount} targets for $composed composed items",
            )
        }
    }

    @Test
    fun restoreScrollsToTheItemOfAFocusedRowThatIsGone() {
        // No first row fallback, so only the remembered row can take focus.
        val host = ListHost(focusFirstOnEnter = false)
        withList(host) {
            repeat(3) { press(Key.Tab) }
            assertEquals(listOf("Sub A"), focusedTexts())
            // Focus leaves the list, so its rows may go away.
            press(Key.DirectionUp)
            assertEquals(listOf("Field"), focusedTexts())

            // No keys, like a busy list. Then the rows scroll away.
            host.ready = false
            draw()
            host.listState.requestScrollToItem(ROW_COUNT)
            draw()
            assertNull(nodeWithTextOrNull("Sub A"), "The list did not scroll")

            // The row is not composed, its lazy item is scrolled to.
            host.ready = true
            draw()
            draw()
            assertEquals(listOf("Sub A"), focusedTexts())
        }
    }

    @Test
    fun aSideEntryFocusesTheRowFocusedLast() {
        val host = SideHost()
        withEntry({ SideContent(host) }) {
            assertEquals(listOf("Row 1"), focusedTexts())
            repeat(3) { press(Key.DirectionDown) }
            assertEquals(listOf("Row 4"), focusedTexts())
            press(Key.DirectionLeft)
            assertEquals(listOf("Side 4"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Side 5"), focusedTexts())

            // Not Row 5, which is level with the button.
            press(Key.DirectionRight)
            assertEquals(listOf("Row 4"), focusedTexts())
        }
    }

    @Test
    fun aSideEntryWithNoRowFocusedGoesWhereTheSearchFinds() {
        val host = SideHost(focusFirstOnEnter = false)
        withEntry({ SideContent(host) }) {
            // The pane comes first.
            press(Key.Tab)
            assertEquals(listOf("Side 1"), focusedTexts())
            repeat(2) { press(Key.DirectionDown) }
            assertEquals(listOf("Side 3"), focusedTexts())

            press(Key.DirectionRight)
            assertEquals(listOf("Row 3"), focusedTexts())
        }
    }

    @Test
    fun aSideEntryGoesWhereTheSearchFindsIfTheRowIsNotComposed() {
        val host = SideHost()
        withEntry({ SideContent(host) }) {
            press(Key.DirectionDown)
            assertEquals(listOf("Row 2"), focusedTexts())
            press(Key.DirectionLeft)
            assertEquals(listOf("Side 2"), focusedTexts())

            host.listState.requestScrollToItem(SIDE_SCROLL_INDEX)
            draw()
            assertNull(nodeWithTextOrNull("Row 2"), "The list did not scroll")

            // The row level with the button.
            press(Key.DirectionRight)
            assertEquals(listOf("Row ${SIDE_SCROLL_INDEX + 2}"), focusedTexts())
        }
    }

    @Test
    fun topBarArrowKeysStayInTheBarAndDownReturnsToTheRow() {
        val host = TopBarHost()
        withEntry({ TopBarContent(host) }) {
            assertEquals(listOf("Row 1"), focusedTexts())
            // The bar is the exit up of the list.
            press(Key.DirectionUp)
            assertEquals(listOf("Back"), focusedTexts())

            // Left and Right move along the bar and stop at its ends.
            press(Key.DirectionLeft)
            assertEquals(listOf("Back"), focusedTexts())
            press(Key.DirectionRight)
            assertEquals(listOf("Edit"), focusedTexts())
            press(Key.DirectionRight)
            assertEquals(listOf("More"), focusedTexts())
            press(Key.DirectionRight)
            assertEquals(listOf("More"), focusedTexts())
            // Up does nothing.
            press(Key.DirectionUp)
            assertEquals(listOf("More"), focusedTexts())

            press(Key.DirectionDown)
            assertEquals(listOf("Row 1"), focusedTexts())
            assertEquals(InputMode.Keyboard, inputMode)
        }
    }

    @Test
    fun aHandOffSkipsATargetOutsideOfTheList() {
        val host = TopBarHost()
        withEntry({ TopBarContent(host) }) {
            repeat(2) { press(Key.DirectionDown) }
            assertEquals(listOf("Row 3"), focusedTexts())
            // The entry remembers the button, as a screen pushed from it would.
            host.editRequester.requestFocus()
            draw()
            assertEquals(listOf("Edit"), focusedTexts())

            press(Key.DirectionDown)
            assertEquals(listOf("Row 3"), focusedTexts())
        }
    }

    @Test
    fun aHandOffWithNoRowFocusedFocusesTheFirstRow() {
        val host = TopBarHost(focusFirstOnEnter = false)
        withEntry({ TopBarContent(host) }) {
            // The bar comes first.
            press(Key.Tab)
            assertEquals(listOf("Back"), focusedTexts())
            press(Key.DirectionRight)
            assertEquals(listOf("Edit"), focusedTexts())

            press(Key.DirectionDown)
            assertEquals(listOf("Row 1"), focusedTexts())
        }
    }
}

private const val ROW_COUNT = 100

private const val SCROLL_STEP = 10

/** The block holds two targets, and a few items may be prefetched. */
private const val TARGET_COUNT_SLACK = 4

private const val BLOCK_KEY = "block"

private const val SIDE_COUNT = 6

/** Scrolls the rows next to the side buttons far from the top. */
private const val SIDE_SCROLL_INDEX = 50

private const val EDIT_KEY = "edit"

private class ListHost(
    val exitUpEnabled: Boolean = true,
    val focusFirstOnEnter: Boolean = true,
) {
    val listState = LazyListState()

    /** `false` passes no keys, so a restore waits. */
    var ready by mutableStateOf(true)

    var focus: ListFocus? = null
}

private fun ListFocusSceneTest.withList(
    host: ListHost,
    test: ImageComposeScene.() -> Unit,
) {
    withEntry({ ListContent(host) }, test)
}

/** Runs [test] against [content] that one navigation entry hosts. */
private fun ListFocusSceneTest.withEntry(
    content: @Composable () -> Unit,
    test: ImageComposeScene.() -> Unit,
) {
    val entry = NavigationEntryImpl(
        source = "test",
        parent = entryScope,
        id = "list",
        route = UnusedRoute,
    )
    withScene({
        CompositionLocalProvider(
            LocalNavigationEntry provides entry,
        ) {
            content()
        }
    }, test = test)
}

/**
 * A field and a Filter button above a list. The list starts with a
 * block of two rows that share one lazy item, then the plain rows.
 */
@Composable
private fun ListContent(host: ListHost) {
    val rows = rememberRows()
    val keys = remember(rows) {
        listOf(BLOCK_KEY) + rows
    }
    val exitUp = remember { FocusRequester() }
    val focus = rememberListFocus(
        listState = host.listState,
        listKeys = if (host.ready) keys else emptyList(),
        focusFirstOnEnter = host.focusFirstOnEnter,
        exitUp = exitUp,
    )
    host.focus = focus
    Column {
        TextButton(
            modifier = Modifier
                .focusRequester(exitUp),
            enabled = host.exitUpEnabled,
            onClick = {},
        ) {
            Text("Field")
        }
        TextButton(
            onClick = {},
        ) {
            Text("Filter")
        }
        LazyColumn(
            modifier = focus.listModifier,
            state = host.listState,
        ) {
            item(BLOCK_KEY) {
                Column {
                    listOf("Sub A", "Sub B").forEach { title ->
                        ListRow(
                            modifier = focus.target("$BLOCK_KEY:$title", itemKey = BLOCK_KEY),
                            title = title,
                        )
                    }
                }
            }
            plainRows(focus, rows)
        }
    }
}

private class SideHost(
    val focusFirstOnEnter: Boolean = true,
) {
    val listState = LazyListState()
}

/**
 * A pane of buttons next to a list, like a pane next to a list of items.
 * Each button is level with a row.
 */
@Composable
private fun SideContent(host: SideHost) {
    val rows = rememberRows()
    val focus = rememberListFocus(
        listState = host.listState,
        listKeys = rows,
        focusFirstOnEnter = host.focusFirstOnEnter,
    )
    Row {
        Column(
            modifier = Modifier
                .weight(1f)
                .arrowKeyFocusNavigation(),
        ) {
            for (index in 1..SIDE_COUNT) {
                ListRow(
                    modifier = Modifier,
                    title = "Side $index",
                )
            }
        }
        LazyColumn(
            modifier = focus.listModifier
                .weight(1f),
            state = host.listState,
        ) {
            plainRows(focus, rows)
        }
    }
}

private class TopBarHost(
    val focusFirstOnEnter: Boolean = true,
) {
    val listState = LazyListState()

    /** Focuses the Edit button, as a screen that closes restores it. */
    val editRequester = FocusRequester()
}

/**
 * A top bar of Back, Edit and More above a list, linked to the list
 * like a scaffold does. Edit is a target outside of the list.
 */
@Composable
private fun TopBarContent(host: TopBarHost) {
    val rows = rememberRows()
    val focus = rememberListFocus(
        listState = host.listState,
        listKeys = rows,
        focusFirstOnEnter = host.focusFirstOnEnter,
    )
    val contentFocus = focus.rememberScaffoldContentFocus(topBar = true)
    Column {
        Box(
            modifier = checkNotNull(contentFocus.topBar),
        ) {
            Row {
                TextButton(
                    onClick = {},
                ) {
                    Text("Back")
                }
                TextButton(
                    modifier = Modifier
                        .focusRequester(host.editRequester)
                        .then(focus.target(EDIT_KEY, inList = false)),
                    onClick = {},
                ) {
                    Text("Edit")
                }
                TextButton(
                    onClick = {},
                ) {
                    Text("More")
                }
            }
        }
        LazyColumn(
            modifier = focus.listModifier,
            state = host.listState,
        ) {
            plainRows(focus, rows)
        }
    }
}

@Composable
private fun rememberRows(): List<String> = remember {
    (1..ROW_COUNT).map { "row$it" }
}

private fun LazyListScope.plainRows(
    focus: ListFocus,
    rows: List<String>,
) {
    items(rows, key = { it }) { key ->
        ListRow(
            modifier = focus.target(key),
            title = "Row ${key.removePrefix("row")}",
        )
    }
}

@Composable
private fun ListRow(
    modifier: Modifier,
    title: String,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable {},
    ) {
        Text(title)
    }
}

private object UnusedRoute : Route {
    @Composable
    override fun Content() = Unit
}
