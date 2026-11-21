@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.settings.accounts.AccountListState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.settings.search.SearchSettingsScreenContent
import com.artemchep.keyguard.feature.home.settings.search.SearchSettingsState
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.feature.navigation.twoPaneHost
import com.artemchep.keyguard.feature.navigation.withNavigationHarness
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.assertFocusedFullyInView
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Keyboard paths through the shared Settings rows. */
class SettingsKeyboardSceneTest {
    // Internal, so the scene helpers below the class can reach it.
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun switchRowIsOneTabStopAndSpaceOrEnterToggleIt() {
        var checked by mutableStateOf(false)
        var opened = 0
        withScene({
            val components = LocalSettingPaneComponents.current
            Column {
                components.KgSwitch(
                    title = "Wi-Fi",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
                components.KgAction(
                    icon = null,
                    title = "About",
                    onClick = { opened++ },
                )
            }
        }) {
            press(Key.Tab)
            val row = focused()
            assertEquals(listOf("Wi-Fi"), row[SemanticsProperties.Text].map { it.text })
            assertEquals(Role.Switch, row[SemanticsProperties.Role])
            assertEquals(ToggleableState.Off, row[SemanticsProperties.ToggleableState])
            assertNotNull(row.getOrNull(SemanticsActions.OnClick))

            press(Key.Spacebar)
            assertTrue(checked)
            assertEquals(ToggleableState.On, focused()[SemanticsProperties.ToggleableState])
            press(Key.Enter)
            assertFalse(checked)
            assertEquals(ToggleableState.Off, focused()[SemanticsProperties.ToggleableState])

            // The thumb is not a second Tab stop.
            press(Key.Tab)
            assertEquals(listOf("About"), focusedTexts())
            assertEquals(0, opened)
            press(Key.Tab, shift = true)
            assertEquals(listOf("Wi-Fi"), focusedTexts())
        }
    }

    @Test
    fun pickerOpensOnTheSelectedOptionFromTheKeyboard() {
        var selected by mutableStateOf(1)
        withPicker(selected = { selected }, onSelect = { selected = it }) {
            press(Key.Tab)
            assertEquals(listOf("Length", "Two"), focusedTexts())

            // The option has focus before the open animation runs.
            press(Key.Enter)
            assertEquals(listOf("Two"), focusedTexts())
            advanceTimeBy(MENU_ANIMATION_MILLIS)
            assertEquals(listOf("Two"), focusedTexts())

            // Arrow keys still move between the options.
            press(Key.DirectionDown)
            assertEquals(listOf("Three"), focusedTexts())

            press(Key.Escape)
            advanceTimeBy(MENU_ANIMATION_MILLIS)
            assertMenuClosed()
            assertEquals(1, selected)
            assertEquals(listOf("Length", "Two"), focusedTexts())

            // Picking an option also returns to the row.
            press(Key.Enter)
            advanceTimeBy(MENU_ANIMATION_MILLIS)
            assertEquals(listOf("Two"), focusedTexts())
            press(Key.DirectionUp)
            press(Key.Enter)
            advanceTimeBy(MENU_ANIMATION_MILLIS)
            assertMenuClosed()
            assertEquals(0, selected)
            assertEquals(listOf("Length", "One"), focusedTexts())
        }
    }

    @Test
    fun pickerOpenedWithTheMouseFocusesNoOption() {
        var selected by mutableStateOf(1)
        withPicker(selected = { selected }, onSelect = { selected = it }) {
            val row = nodeWithText("Length")
            mouseClick(row.boundsInRoot.center)
            assertEquals(InputMode.Touch, inputMode)
            advanceTimeBy(MENU_ANIMATION_MILLIS)
            assertNotNull(menuOption("Two"))
            assertNull(menuNodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true })
        }
    }

    @Test
    fun arrowKeysMoveBetweenPaneRowsAndIntoTheirFooter() {
        withPane({ paneState(mixedRows()) }) {
            // The pane focuses its first row, the section takes no focus.
            assertEquals(listOf("Wi-Fi"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("About"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Learn more"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Length", "Two"), focusedTexts())
            // Up goes by position: the row above ends next to this one.
            press(Key.DirectionUp)
            assertEquals(listOf("About"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Learn more"), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf("About"), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf("Wi-Fi"), focusedTexts())
            assertEquals(InputMode.Keyboard, inputMode)
        }
    }

    @Test
    fun paneFocusesItsFirstRowOnceLoadedAndTabKeepsWorking() {
        var state by mutableStateOf(NOT_READY_PANE)
        withPane({ state }) {
            assertNull(focusedOrNull())

            state = paneState(mixedRows())
            draw()
            assertEquals(listOf("Wi-Fi"), focusedTexts())

            // Tab switches nothing that would restore focus again.
            press(Key.Tab)
            assertEquals(listOf("About"), focusedTexts())
            advanceTimeBy(1_000L)
            assertEquals(listOf("About"), focusedTexts())
        }
    }

    @Test
    fun paneFocusesNothingInTouchMode() {
        var state by mutableStateOf(NOT_READY_PANE)
        withPane({ state }) {
            mouseClick(Offset(450f, 600f))
            assertEquals(InputMode.Touch, inputMode)

            state = paneState(mixedRows())
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())
        }
    }

    @Test
    fun paneRestoresTheRowOfItsEntryWhenItComesBack() {
        var entry by mutableStateOf(newEntry())
        var shown by mutableStateOf(true)
        val state = paneState(
            (1..ROW_COUNT).map { i ->
                "row$i" to @Composable { ActionRow("Row $i") }
            },
        )
        withPane({ state }, entry = { entry }, shown = { shown }) {
            assertEquals(listOf("Row 1"), focusedTexts())
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            assertEquals(listOf("Row 3"), focusedTexts())

            fun reAdd() {
                shown = false
                draw()
                assertNull(focusedOrNull())
                shown = true
                draw()
            }

            reAdd()
            assertEquals(listOf("Row 3"), focusedTexts())

            // A row below the first screen: the new list starts at the top.
            repeat(FAR_ROW - 3) {
                press(Key.DirectionDown)
                // The list scrolls the focused row into view.
                advanceTimeBy(SCROLL_ANIMATION_MILLIS)
            }
            assertEquals(listOf("Row $FAR_ROW"), focusedTexts())
            reAdd()
            assertEquals(listOf("Row $FAR_ROW"), focusedTexts())
            assertNull(nodeWithTextOrNull("Row 1"), "The list did not scroll")

            // An ended entry is forgotten, even if a new one reuses its id.
            entry.destroy()
            entry = newEntry()
            reAdd()
            assertEquals(listOf("Row 1"), focusedTexts())
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun searchDownFocusesTheFirstResultAndSideArrowsStayInTheField() {
        var query by mutableStateOf("ab")
        val dispatcher = UnconfinedTestDispatcher()
        withScene({
            val state = SearchSettingsState(
                query = TextFieldModel(text = query, onChange = { query = it }),
                items = listOf("Alpha", "Beta").map { title ->
                    SearchSettingsState.Item.Settings(
                        key = title,
                        score = 1f,
                        content = { ActionRow(title) },
                    )
                },
            )
            SearchSettingsScreenContent(state)
        }, dispatcher) {
            // Tab reaches the field before its delayed focus.
            press(Key.Tab)
            assertEquals("ab", focused()[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(2), focused()[SemanticsProperties.TextSelectionRange])
            press(Key.DirectionLeft)
            assertEquals(TextRange(1), focused()[SemanticsProperties.TextSelectionRange])
            press(Key.DirectionRight)
            assertEquals(TextRange(2), focused()[SemanticsProperties.TextSelectionRange])

            press(Key.DirectionDown)
            assertEquals(listOf("Alpha"), focusedTexts())
            // The delayed field focus does not take focus back.
            dispatcher.scheduler.advanceTimeBy(1_000L)
            draw()
            assertEquals(listOf("Alpha"), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf("Beta"), focusedTexts())
            assertEquals("ab", query)
        }
    }

    @Test
    fun settingListRestoresTheRowWhenItsDetailCloses() {
        listOf(
            NavigationComposeHarness.WIDE_WIDTH to NavigationComposeHarness.WIDE_HEIGHT,
            NavigationComposeHarness.NARROW_WIDTH to NavigationComposeHarness.NARROW_HEIGHT,
        ).forEach { (width, height) ->
            withNavigationHarness {
                val items = listOf("Alpha", "Beta").map { title ->
                    SettingsItem(
                        id = title,
                        title = TextHolder.Value(title),
                        text = TextHolder.Value(""),
                        route = DetailRoute("$title row"),
                    )
                }
                start(twoPaneHost(SettingsRoute.ROUTER_NAME, SettingListTestRoute(items)), width, height)
                val size = "${width}x$height"

                // Opening the list moves no focus.
                assertNull(scene.focusedOrNull(), size)
                repeat(4) {
                    if ("Alpha" !in scene.focusedTexts()) press(Key.Tab)
                }
                assertEquals(listOf("Alpha"), scene.focusedTexts(), size)
                press(Key.DirectionDown)
                assertEquals(listOf("Beta"), scene.focusedTexts(), size)

                // The detail focuses its first row.
                press(Key.Enter)
                assertEquals(listOf("Beta row"), scene.focusedTexts(), size)

                router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
                repeat(2) { frame() }
                assertEquals(listOf("Beta"), scene.focusedTexts(), size)
            }
        }
    }

    @Test
    fun settingListRestoresTheAccountRowWhenItsDetailCloses() {
        listOf(
            NavigationComposeHarness.WIDE_WIDTH to NavigationComposeHarness.WIDE_HEIGHT,
            NavigationComposeHarness.NARROW_WIDTH to NavigationComposeHarness.NARROW_HEIGHT,
        ).forEach { (width, height) ->
            withNavigationHarness {
                val route = SettingListAccountsTestRoute(listOf("Alpha", "Beta", "Gamma"))
                start(twoPaneHost(SettingsRoute.ROUTER_NAME, route), width, height)
                val size = "${width}x$height"

                repeat(4) {
                    if ("Alpha" !in scene.focusedTexts()) press(Key.Tab)
                }
                assertEquals(listOf("Alpha"), scene.focusedTexts(), size)
                press(Key.DirectionDown)
                assertEquals(listOf("Beta"), scene.focusedTexts(), size)

                press(Key.Enter)
                assertEquals(listOf("Beta account"), scene.focusedTexts(), size)

                // The account the user left, not the first one.
                router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
                repeat(2) { frame() }
                assertEquals(listOf("Beta"), scene.focusedTexts(), size)
            }
        }
    }
}

/** The keyboard focus restore of the Settings list with its saved scroll position. */
class SettingListScrollKeyboardSceneTest {
    @Test
    fun settingListRestoresAFarRowAndItsScrollWhenItsDetailCloses() = withNavigationHarness {
        val items = (1..ROW_COUNT).map { i ->
            SettingsItem(
                id = "item$i",
                title = TextHolder.Value("Category $i"),
                text = TextHolder.Value(""),
                route = DetailRoute("Category $i row"),
            )
        }
        start(
            route = twoPaneHost(SettingsRoute.ROUTER_NAME, SettingListTestRoute(items)),
            width = NavigationComposeHarness.NARROW_WIDTH,
            height = NavigationComposeHarness.NARROW_HEIGHT,
        )
        repeat(4) {
            if ("Category 1" !in scene.focusedTexts()) press(Key.Tab)
        }
        assertEquals(listOf("Category 1"), scene.focusedTexts())
        // A row below the first screen.
        repeat(FAR_ROW - 1) { press(Key.DirectionDown) }
        val title = "Category $FAR_ROW"
        assertEquals(listOf(title), scene.focusedTexts())
        assertNull(scene.nodeWithTextOrNull("Category 1"), "The list did not scroll")
        val listEntry = router(SettingsRoute.ROUTER_NAME).entries.first()
        val saved = assertNotNull(listEntry.scrollPosition)

        press(Key.Enter)
        assertEquals(listOf("$title row"), scene.focusedTexts())
        router(SettingsRoute.ROUTER_NAME).controller.queue(NavigationIntent.Pop)
        repeat(2) { frame() }

        // The saved scroll and the focus restore agree.
        assertEquals(listOf(title), scene.focusedTexts())
        assertEquals(saved, listEntry.scrollPosition)
        assertNull(scene.nodeWithTextOrNull("Category 1"), "The list jumped to the top")
        scene.assertFocusedFullyInView()
        repeat(2) { frame() }
        assertEquals(listOf(title), scene.focusedTexts())
        assertEquals(saved, listEntry.scrollPosition)
    }
}

private val OPTIONS = listOf("One", "Two", "Three")

private const val MENU_ANIMATION_MILLIS = 1_000L

/**
 * Not ready, like Loading. The Loading skeleton reads the
 * AWT window, which a headless test run cannot create.
 */
private val NOT_READY_PANE = paneState(emptyList())

private const val SCROLL_ANIMATION_MILLIS = 500L

private const val ROW_COUNT = 40

/** Below the first screen of rows. */
private const val FAR_ROW = 25

private class SettingListTestRoute(
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

/** The Settings list with only the accounts block. An account opens a [DetailRoute]. */
private class SettingListAccountsTestRoute(
    private val accounts: List<String>,
) : Route {
    @Composable
    override fun Content() {
        val controller by rememberUpdatedState(LocalNavigationController.current)
        val accountsState = remember {
            val state = accountListState(accounts) { title ->
                // Like an account in the app: the detail replaces the open one.
                val intent = NavigationIntent.Composite(
                    listOf(
                        NavigationIntent.PopById(SettingsRoute.ROUTER_NAME),
                        NavigationIntent.NavigateToRoute(DetailRoute("$title account")),
                    ),
                )
                controller.queue(intent)
            }
            mutableStateOf(state)
        }
        val items = remember(accountsState) {
            listOf(SettingsAccountsItem(id = "accounts", state = accountsState))
        }
        WizardPreview {
            SettingListScreenContent(
                accountsState = accountsState,
                items = items,
            )
        }
    }
}

/** A loaded accounts state with an account per title. */
private fun accountListState(
    titles: List<String>,
    onClick: (String) -> Unit,
) = AccountListState(
    items = titles.map { title ->
        AccountItem.Item(
            id = title.lowercase(),
            name = title,
            title = AnnotatedString(title),
            text = null,
            hidden = false,
            premium = false,
            actionNeeded = false,
            icon = VaultItemIcon.VectorIcon(Icons.Outlined.Person),
            accentLight = Color.Blue,
            accentDark = Color.Cyan,
            isOpened = false,
            isSelected = false,
            onClick = { onClick(title) },
            onLongClick = null,
        )
    },
    isLoading = false,
)

private class DetailRoute(
    private val row: String,
) : Route {
    @Composable
    override fun Content() {
        WizardPreview {
            SettingPaneContent2(
                title = "Detail",
                state = paneState(
                    listOf(
                        "row" to @Composable { ActionRow(row) },
                    ),
                ),
            )
        }
    }
}

/** A Settings row that does nothing on a click. */
@Composable
internal fun ActionRow(title: String) {
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = title,
        onClick = {},
    )
}

private fun SettingsKeyboardSceneTest.newEntry(): NavigationEntry = NavigationEntryImpl(
    source = "test",
    parent = entryScope,
    id = "pane",
    route = DetailRoute("unused"),
)

/** A pane under a navigation entry, the way a route shows it. */
private fun SettingsKeyboardSceneTest.withPane(
    state: () -> SettingPaneState,
    entry: () -> NavigationEntry = run {
        val e = newEntry()
        ({ e })
    },
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

/** A section, a switch, an action with a footer button and a picker. */
private fun mixedRows(): List<Pair<String, @Composable () -> Unit>> = listOf(
    "section" to @Composable {
        Section(text = "General")
    },
    "switch" to @Composable {
        LocalSettingPaneComponents.current.KgSwitch(
            title = "Wi-Fi",
            checked = false,
            onCheckedChange = {},
        )
    },
    "action" to @Composable {
        LocalSettingPaneComponents.current.KgAction(
            icon = null,
            title = "About",
            onClick = {},
            footer = {
                TextButton(onClick = {}) {
                    Text("Learn more")
                }
            },
        )
    },
    "picker" to @Composable {
        LocalSettingPaneComponents.current.KgPicker(
            title = "Length",
            text = "Two",
            dropdown = OPTIONS.map { title ->
                FlatItemAction(
                    title = TextHolder.Value(title),
                    selected = title == "Two",
                    onClick = {},
                )
            },
        )
    },
)

private fun withPicker(
    selected: () -> Int,
    onSelect: (Int) -> Unit,
    test: ImageComposeScene.() -> Unit,
) = withScene({
    val components = LocalSettingPaneComponents.current
    val index = selected()
    components.KgPicker(
        title = "Length",
        text = OPTIONS[index],
        dropdown = OPTIONS.mapIndexed { i, title ->
            FlatItemAction(
                title = TextHolder.Value(title),
                selected = i == index,
                onClick = { onSelect(i) },
            )
        },
    )
}, test = test)

/** The menu is a popup, so it is not on the first, main layer. */
private const val MENU_FIRST_LAYER = 1

private fun ImageComposeScene.menuNodes(): List<SemanticsNode> = mergedNodes(MENU_FIRST_LAYER)

private fun ImageComposeScene.menuOption(text: String) = nodeWithTextOrNull(text, MENU_FIRST_LAYER)

private fun ImageComposeScene.assertMenuClosed() {
    assertNull(menuOption("One"), "The menu is still open")
}

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
