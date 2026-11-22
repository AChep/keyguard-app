@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.feature.home.settings.accounts.AccountListState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeFinishing
import com.artemchep.keyguard.feature.navigation.NavigationBackEdge
import com.artemchep.keyguard.feature.navigation.NavigationBackEvent
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationListScrollState
import com.artemchep.keyguard.feature.navigation.NavigationScrollPosition
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.rememberNavigationListScrollState
import com.artemchep.keyguard.feature.navigation.twoPaneHost
import com.artemchep.keyguard.feature.navigation.withNavigationHarness
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.wheelScrollDown
import com.artemchep.keyguard.ui.withScene
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Scroll position preservation across back navigation on the basic Settings screens. */
class SettingsScrollSceneTest {
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun settingPanePreservesScrollPositionInTouchModeWhenItComesBack() {
        val entry = newEntry()
        var shown by mutableStateOf(false)
        val state = rowsPaneState(1..ROW_COUNT)

        withPane({ state }, entry = { entry }, shown = { shown }) {
            // Enter touch mode before showing the pane so Row 1 is not focused/pinned.
            mouseClick(Offset(450f, 350f))
            assertEquals(InputMode.Touch, inputMode)
            shown = true
            draw()

            wheelScrollDown(Offset(450f, 350f), steps = 20)
            val savedBeforeLeave = assertNotNull(entry.viewMemory.scrollPosition)
            assertTrue(savedBeforeLeave.itemIndex > 0, "Expected scrolled list, got $savedBeforeLeave")
            assertNull(nodeWithTextOrNull("Row 1"))
            val visibleKey = assertNotNull(savedBeforeLeave.itemKey)

            shown = false
            draw()
            shown = true
            draw()

            val savedAfterReturn = assertNotNull(entry.viewMemory.scrollPosition)
            assertEquals(visibleKey, savedAfterReturn.itemKey)
            assertEquals(savedBeforeLeave.itemIndex, savedAfterReturn.itemIndex)
            assertEquals(savedBeforeLeave.itemOffset, savedAfterReturn.itemOffset)
            assertNull(nodeWithTextOrNull("Row 1"))
        }
    }

    @Test
    fun settingPanePreservesScrollPositionAcrossDelayedLoading() {
        val entry = newEntry()
        var shown by mutableStateOf(true)
        var state by mutableStateOf(rowsPaneState(1..ROW_COUNT))

        withPane({ state }, entry = { entry }, shown = { shown }) {
            mouseClick(Offset(450f, 350f))
            wheelScrollDown(Offset(450f, 350f), steps = 16)
            val expected = assertNotNull(entry.viewMemory.scrollPosition)
            assertTrue(expected.itemIndex > 0)

            // Leave and come back while rows are not ready yet (mirrors Loading -> Ok).
            shown = false
            draw()
            state = NOT_READY_PANE
            shown = true
            draw()

            // Loading must not overwrite the saved position.
            assertEquals(expected, entry.viewMemory.scrollPosition)

            // Once loaded, the saved scroll position restores immediately.
            state = rowsPaneState(1..ROW_COUNT)
            draw()

            val restored = assertNotNull(entry.viewMemory.scrollPosition)
            assertEquals(expected.itemKey, restored.itemKey)
            assertEquals(expected.itemIndex, restored.itemIndex)
            assertEquals(expected.itemOffset, restored.itemOffset)
            assertNull(nodeWithTextOrNull("Row 1"))
        }
    }

    @Test
    fun settingPaneFollowsItemKeyWhenRowsAreInsertedAbove() {
        val entry = newEntry()
        var shown by mutableStateOf(true)
        var state by mutableStateOf(rowsPaneState(1..ROW_COUNT))

        withPane({ state }, entry = { entry }, shown = { shown }) {
            mouseClick(Offset(450f, 350f))
            wheelScrollDown(Offset(450f, 350f), steps = 14)
            val saved = assertNotNull(entry.viewMemory.scrollPosition)
            assertTrue(saved.itemIndex > 0)

            shown = false
            draw()

            val prepended = (1..3).map { i -> "extra$i" to @Composable { ScrollActionRow("Extra $i") } }
            val original = (1..ROW_COUNT).map { i -> "row$i" to @Composable { ScrollActionRow("Row $i") } }
            state = paneState(prepended + original)
            shown = true
            draw()

            val restored = assertNotNull(entry.viewMemory.scrollPosition)
            assertEquals(saved.itemKey, restored.itemKey)
            assertEquals(saved.itemIndex + 3, restored.itemIndex)
            assertEquals(saved.itemOffset, restored.itemOffset)
        }
    }

    @Test
    fun settingPaneResetsScrollPositionForNewNavigationEntry() {
        var entry by mutableStateOf(newEntry())
        var shown by mutableStateOf(false)
        val state = rowsPaneState(1..ROW_COUNT)

        withPane({ state }, entry = { entry }, shown = { shown }) {
            mouseClick(Offset(450f, 350f))
            shown = true
            draw()
            wheelScrollDown(Offset(450f, 350f), steps = 20)
            assertTrue(assertNotNull(entry.viewMemory.scrollPosition).itemIndex > 0)
            assertNull(nodeWithTextOrNull("Row 1"))

            // Destroy and replace with a new entry reusing the same id.
            shown = false
            draw()
            entry.destroy()
            entry = newEntry()
            shown = true
            draw()

            val fresh = assertNotNull(entry.viewMemory.scrollPosition)
            assertEquals(0, fresh.itemIndex)
            assertEquals(0, fresh.itemOffset)
            assertNotNull(nodeWithTextOrNull("Row 1"))
        }
    }

    @Test
    fun settingListPreservesScrollPositionInNarrowModeWhenDetailCloses() {
        withNavigationHarness {
            val items = (1..ROW_COUNT).map { i ->
                SettingsItem(
                    id = "item$i",
                    title = TextHolder.Value("Category $i"),
                    text = TextHolder.Value(""),
                    route = ScrollDetailRoute("Category $i detail"),
                )
            }
            start(
                route = twoPaneHost(SettingsRoute.ROUTER_NAME, ScrollSettingListTestRoute(items)),
                width = NavigationComposeHarness.NARROW_WIDTH,
                height = NavigationComposeHarness.NARROW_HEIGHT,
            )

            // Switch to touch mode on the toolbar (not a clickable row), then scroll down.
            scene.mouseClick(Offset(160f, 32f))
            scene.wheelScrollDown(Offset(160f, 240f), steps = 14)
            frame()
            val listEntry = router(SettingsRoute.ROUTER_NAME).entries.first()
            val saved = assertNotNull(listEntry.viewMemory.scrollPosition)
            assertTrue(saved.itemIndex > 0, "Expected scrolled settings list, got $saved")
            assertNull(scene.nodeWithTextOrNull("Category 1"))

            // Tap a visible scrolled row to open its detail pane.
            val targetTitle = "Category ${saved.itemIndex + 2}"
            val targetRow = scene.nodeWithText(targetTitle)
            scene.mouseClick(targetRow.boundsInRoot.center)
            frame()
            assertTrue(hasText("$targetTitle detail"))

            // Pop back to SettingListScreen and verify exact scroll restoration.
            router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
            repeat(2) { frame() }

            val restored = assertNotNull(listEntry.viewMemory.scrollPosition)
            assertEquals(saved.itemKey, restored.itemKey)
            assertEquals(saved.itemIndex, restored.itemIndex)
            assertEquals(saved.itemOffset, restored.itemOffset)
            assertNull(scene.nodeWithTextOrNull("Category 1"))
        }
    }

    @Test
    fun settingPanePreservesScrollPositionWhenSubRouteClosesInWideAndNarrowModes() {
        val animations = listOf(NavAnimation.DISABLED, NavAnimation.DYNAMIC)
        val sizes = listOf(
            NavigationComposeHarness.WIDE_WIDTH to NavigationComposeHarness.WIDE_HEIGHT,
            NavigationComposeHarness.NARROW_WIDTH to NavigationComposeHarness.NARROW_HEIGHT,
        )
        animations.forEach { animation ->
            sizes.forEach { (width, height) ->
                verifySubRouteBackRestoresPaneScroll(animation, width, height)
            }
        }
    }

    @Test
    fun cancelledPredictiveBackPreviewDoesNotOverwriteSavedScrollPosition() {
        withNavigationHarness(navAnimation = NavAnimation.DYNAMIC) {
            val categoryRoute = DelayedScrollPaneCategoryRoute(ROW_COUNT)
            val items = listOf(
                SettingsItem(
                    id = "cat",
                    title = TextHolder.Value("Category"),
                    text = TextHolder.Value(""),
                    route = categoryRoute,
                ),
            )
            start(
                route = twoPaneHost(SettingsRoute.ROUTER_NAME, ScrollSettingListTestRoute(items)),
                width = NavigationComposeHarness.NARROW_WIDTH,
                height = NavigationComposeHarness.NARROW_HEIGHT,
            )

            // Open category pane, load it, and scroll down.
            scene.mouseClick(scene.nodeWithText("Category").boundsInRoot.center)
            repeat(2) { frame() }
            categoryRoute.loaded = true
            frame()

            val paneEntry = router(SettingsRoute.ROUTER_NAME).entries.last()
            scene.wheelScrollDown(Offset(160f, 240f), steps = 14)
            frame()
            val saved = assertNotNull(paneEntry.viewMemory.scrollPosition)
            assertTrue(saved.itemIndex > 0)

            // Push a sub-route on top.
            router(SettingsRoute.ROUTER_NAME).controller.queue(
                NavigationIntent.NavigateToRoute(ScrollDetailRoute("Deep")),
            )
            repeat(2) { frame() }
            assertTrue(hasText("Deep"))

            // Simulate the underlying pane starting not-ready during predictive back preview, then cancel.
            categoryRoute.loaded = false
            host.startBack(leftEdgeBackEvent(progress = 0.2f, touchX = 0f))
            render(16_000_000L)
            host.progressBack(leftEdgeBackEvent(progress = 0.4f, touchX = 60f))
            render(16_000_000L)
            host.cancelBack()
            repeat(2) { frame() }

            // Saved position must remain untouched.
            assertEquals(saved, paneEntry.viewMemory.scrollPosition)

            // Now actually pop and finish loading -> restores exact saved position.
            router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
            repeat(2) { frame() }
            categoryRoute.loaded = true
            repeat(2) { frame() }

            val restored = assertNotNull(paneEntry.viewMemory.scrollPosition)
            assertEquals(saved.itemKey, restored.itemKey)
            assertEquals(saved.itemIndex, restored.itemIndex)
            assertEquals(saved.itemOffset, restored.itemOffset)
            assertNull(scene.nodeWithTextOrNull("Row 1"))
        }
    }

    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun topAppBarScrollStateIsRestoredAndClampedWhenBoundsShrink() {
        val entry = newEntry()
        entry.viewMemory.scrollPosition = NavigationScrollPosition(
            itemKey = "row5",
            itemIndex = 4,
            itemOffset = 12,
            toolbarHeightOffsetLimit = -240f,
            toolbarHeightOffset = -240f,
            toolbarContentOffset = -900f,
        )
        var captured: NavigationListScrollState? = null
        var toolbarLimit by mutableStateOf(-240f)
        var density by mutableStateOf(Density(1f))

        withScene({
            CompositionLocalProvider(
                LocalNavigationEntry provides entry,
                LocalNavigationNodeFinishing provides false,
                LocalDensity provides density,
            ) {
                val limit = toolbarLimit
                val state = rememberNavigationListScrollState(
                    listKeys = listOf("row1", "row2", "row3", "row4", "row5"),
                )
                captured = state
                SideEffect {
                    state.topAppBarState.heightOffsetLimit = limit
                }
            }
        }) {
            val state = assertNotNull(captured)
            assertEquals(4, state.listState.firstVisibleItemIndex)
            assertEquals(12, state.listState.firstVisibleItemScrollOffset)
            assertEquals(-240f, state.topAppBarState.heightOffsetLimit)
            assertEquals(-240f, state.topAppBarState.heightOffset)
            assertEquals(-900f, state.topAppBarState.contentOffset)

            // Shrinking the toolbar collapse limit on a density/config change clamps heightOffset.
            toolbarLimit = -180f
            density = Density(1.25f)
            advanceTimeBy(64L)
            assertEquals(-180f, state.topAppBarState.heightOffset)
        }
    }
}

private fun SettingsScrollSceneTest.newEntry(): NavigationEntry = NavigationEntryImpl(
    source = "test",
    parent = entryScope,
    id = "pane",
    route = ScrollDetailRoute("unused"),
)

private fun SettingsScrollSceneTest.withPane(
    state: () -> SettingPaneState,
    entry: () -> NavigationEntry,
    shown: () -> Boolean = { true },
    test: ImageComposeScene.() -> Unit,
) = withScene({
    CompositionLocalProvider(
        LocalNavigationEntry provides entry(),
    ) {
        if (shown()) {
            SettingPaneContent2(
                title = "Pane",
                state = state(),
            )
        }
    }
}, test = test)

private fun verifySubRouteBackRestoresPaneScroll(
    animation: NavAnimation,
    width: Int,
    height: Int,
) = withNavigationHarness(navAnimation = animation) {
    val categoryRoute = ScrollPaneCategoryRoute(ROW_COUNT)
    val items = listOf(
        SettingsItem(
            id = "cat",
            title = TextHolder.Value("Category"),
            text = TextHolder.Value(""),
            route = categoryRoute,
        ),
    )
    start(
        route = twoPaneHost(SettingsRoute.ROUTER_NAME, ScrollSettingListTestRoute(items)),
        width = width,
        height = height,
    )

    // Open the category pane.
    val catNode = scene.nodeWithText("Category")
    scene.mouseClick(catNode.boundsInRoot.center)
    repeat(2) { frame() }

    val paneEntry = router(SettingsRoute.ROUTER_NAME).entries.last()
    val scrollPoint = Offset(width * 0.75f, height * 0.5f)
    scene.wheelScrollDown(scrollPoint, steps = 14)
    frame()

    val saved = assertNotNull(paneEntry.viewMemory.scrollPosition, "$animation ${width}x$height")
    assertTrue(saved.itemIndex > 0, "Expected scrolled pane ($animation ${width}x$height), got $saved")
    assertNull(scene.nodeWithTextOrNull("Row 1"))

    // Open a deeper sub-route from the scrolled row.
    val visibleRowNumber = saved.itemIndex + 2
    val rowNode = scene.nodeWithText("Row $visibleRowNumber")
    scene.mouseClick(rowNode.boundsInRoot.center)
    repeat(2) { frame() }
    assertTrue(hasText("Sub-route $visibleRowNumber"), "$animation ${width}x$height")

    // Pop back to the category pane and verify exact scroll restoration.
    router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
    repeat(2) { frame() }

    val restored = assertNotNull(paneEntry.viewMemory.scrollPosition, "$animation ${width}x$height")
    assertEquals(saved.itemKey, restored.itemKey, "$animation ${width}x$height")
    assertEquals(saved.itemIndex, restored.itemIndex, "$animation ${width}x$height")
    assertEquals(saved.itemOffset, restored.itemOffset, "$animation ${width}x$height")
    assertNull(scene.nodeWithTextOrNull("Row 1"), "$animation ${width}x$height")
}

private const val ROW_COUNT = 40

private val NOT_READY_PANE = paneState(emptyList())

private fun leftEdgeBackEvent(
    progress: Float,
    touchX: Float,
) = NavigationBackEvent(
    progress = progress,
    swipeEdge = NavigationBackEdge.LEFT,
    touchX = touchX,
    touchY = 240f,
)

private class ScrollSettingListTestRoute(
    private val items: List<SettingsItem2>,
) : Route {
    @Composable
    override fun Content() {
        WizardPreview {
            SettingListScreenContent(
                accountsState = remember { mutableStateOf(AccountListState()) },
                items = items,
            )
        }
    }
}

private class ScrollPaneCategoryRoute(
    private val count: Int,
) : Route {
    @Composable
    override fun Content() {
        val controller = LocalNavigationController.current
        val state = remember(count, controller) {
            paneState(
                (1..count).map { i ->
                    "row$i" to @Composable {
                        LocalSettingPaneComponents.current.KgAction(
                            icon = null,
                            title = "Row $i",
                            onClick = {
                                controller.queue(
                                    NavigationIntent.NavigateToRoute(ScrollDetailRoute("Sub-route $i")),
                                )
                            },
                        )
                    }
                },
            )
        }
        WizardPreview {
            SettingPaneContent2(
                title = "Category",
                state = state,
            )
        }
    }
}

private class DelayedScrollPaneCategoryRoute(
    private val count: Int,
) : Route {
    var loaded by mutableStateOf(false)

    @Composable
    override fun Content() {
        val rowsState = remember(count) { rowsPaneState(1..count) }
        WizardPreview {
            SettingPaneContent2(
                title = "Category",
                state = if (loaded) rowsState else NOT_READY_PANE,
            )
        }
    }
}

private class ScrollDetailRoute(
    private val row: String,
) : Route {
    @Composable
    override fun Content() {
        WizardPreview {
            SettingPaneContent2(
                title = "Detail",
                state = paneState(
                    listOf(
                        "row" to @Composable { ScrollActionRow(row) },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun ScrollActionRow(title: String) {
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = title,
        onClick = {},
    )
}

private fun rowsPaneState(range: IntRange): SettingPaneState = paneState(
    range.map { i ->
        "row$i" to @Composable { ScrollActionRow("Row $i") }
    },
)

private fun paneState(
    rows: List<Pair<String, @Composable () -> Unit>>,
) = SettingPaneState(
    list = Loadable.Ok(
        rows
            .map { (key, content) ->
                SettingPaneState.Component(
                    compositeKey = key,
                    itemKey = key,
                    groupKey = "",
                    args = null,
                    content = content,
                )
            }
            .toImmutableList(),
    ),
)
