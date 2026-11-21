@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.model.DAccountStatus
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.navigation.HomeNavigationItem
import com.artemchep.keyguard.feature.home.settings.SettingListScreenContent
import com.artemchep.keyguard.feature.home.settings.SettingsItem
import com.artemchep.keyguard.feature.home.settings.SettingsScreen
import com.artemchep.keyguard.feature.home.settings.accounts.AccountListState
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.TYPE_CHIPS
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.typeChip
import com.artemchep.keyguard.feature.home.vault.screen.VaultListScreenContent
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.clickSearchField
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.click
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.feature.navigation.withNavigationHarness
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.dsl.module

/** Keyboard paths between the home navigation and its content. */
class HomeKeyboardSceneTest {
    @Test
    fun railArrowKeysStayInTheRailAndRightReturnsToTheRow() = withHome(
        rows = 3,
        // The screenshot banner below the content is a
        // target for a Down that leaves the rail.
        allowScreenshots = AllowScreenshots.LIMITED,
    ) {
        assertTrue(hasText(SCREENSHOTS_BANNER), "No screenshot banner")
        focusRow("Row 2")
        press(Key.DirectionLeft)
        assertInRail()

        // Up and Down move within the rail and stop at its ends.
        val visited = mutableSetOf<String>()
        repeat(RAIL_FOCUSABLES) {
            press(Key.DirectionUp)
            assertInRail()
            visited += focusedTexts()
        }
        val top = focusedNode().boundsInRoot
        press(Key.DirectionUp)
        assertEquals(top, focusedNode().boundsInRoot)
        repeat(RAIL_FOCUSABLES) {
            press(Key.DirectionDown)
            assertInRail()
            visited += focusedTexts()
        }
        val bottom = focusedNode().boundsInRoot
        assertNotEquals(top, bottom)
        press(Key.DirectionDown)
        assertEquals(bottom, focusedNode().boundsInRoot)
        assertTrue(visited.containsAll(listOf(SETTINGS_LABEL, OTHER_LABEL)), "Visited $visited")

        // Left does nothing, Right goes back to the row.
        press(Key.DirectionLeft)
        assertEquals(bottom, focusedNode().boundsInRoot)
        press(Key.DirectionRight)
        assertEquals(listOf("Row 2"), focusedTexts())
        assertEquals(InputMode.Keyboard, inputMode)
    }

    @Test
    fun rightFromTheRailReturnsToTheVaultRow() = withHome(rows = 3, vault = true) {
        clickSearchField()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals(listOf("Vault row 2"), focusedTexts())

        press(Key.DirectionLeft)
        assertInRail()
        press(Key.DirectionRight)
        assertEquals(listOf("Vault row 2"), focusedTexts())
    }

    @Test
    fun rightFromTheRailReturnsToTheVaultFilter() = withHome(
        rows = 3,
        width = VAULT_FILTERS_WIDTH,
        vault = true,
        vaultChips = TYPE_CHIPS,
    ) {
        clickSearchField()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        assertEquals(listOf(TYPE_CHIPS.first()), focusedTexts())

        // A filter at the start of the second line, not the first filter.
        press(Key.DirectionDown)
        val chip = focusedTexts()
        assertTrue(chip.singleOrNull() in TYPE_CHIPS.drop(1), "Not on line 2: $chip")
        press(Key.DirectionLeft)
        assertInRail()
        press(Key.DirectionRight)
        assertEquals(chip, focusedTexts())
    }

    @Test
    fun rightFromTheRailReturnsToAScrolledRow() = withHome(rows = 40) {
        focusRow("Row 1")
        repeat(FAR_ROW - 1) { press(Key.DirectionDown) }
        assertEquals(listOf("Row $FAR_ROW"), focusedTexts())
        assertFalse(hasText("Row 1"), "The list did not scroll")

        press(Key.DirectionLeft)
        assertInRail()
        press(Key.DirectionUp)
        assertInRail()
        press(Key.DirectionRight)
        assertEquals(listOf("Row $FAR_ROW"), focusedTexts())
    }

    @Test
    fun tabFromTheRailLandsOnTheRow() = withHome(rows = 3) {
        focusRow("Row 2")
        press(Key.DirectionLeft)
        // The last focusable of the rail.
        repeat(RAIL_FOCUSABLES) { press(Key.DirectionDown) }
        assertInRail()

        press(Key.Tab)
        assertEquals(listOf("Row 2"), focusedTexts())
    }

    @Test
    fun enterOnTheRailSwitchesTabsAndKeepsFocusThere() = withHome(rows = 3) {
        focusRow("Row 2")
        press(Key.DirectionLeft)
        focusRailItem(OTHER_LABEL)

        press(Key.Enter)
        assertTrue(hasText(OTHER_ROW), "The tab did not switch")
        assertEquals(listOf(OTHER_LABEL), focusedTexts())

        // Settings remembers its row, but does not take focus.
        focusRailItem(SETTINGS_LABEL)
        press(Key.Enter)
        assertTrue(hasText("Row 2"), "The tab did not switch back")
        repeat(4) { frame() }
        assertEquals(listOf(SETTINGS_LABEL), focusedTexts())

        // The list was composed again: Right enters it from the top.
        press(Key.DirectionRight)
        assertTrue(focusedNode().boundsInRoot.left >= RAIL_MAX_WIDTH, "Focus is still in the rail")
    }

    @Test
    fun barArrowKeysStayInTheBarAndUpReturnsToTheRow() = withHome(
        rows = 2,
        width = NavigationComposeHarness.NARROW_WIDTH,
        height = NavigationComposeHarness.NARROW_HEIGHT,
    ) {
        focusRow("Row 2")
        // The bar is below the last row.
        press(Key.DirectionDown)
        assertInBar()

        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(listOf(SETTINGS_LABEL), focusedTexts())
        press(Key.DirectionRight)
        assertEquals(listOf(OTHER_LABEL), focusedTexts())
        press(Key.DirectionRight)
        assertEquals(listOf(OTHER_LABEL), focusedTexts())
        press(Key.DirectionLeft)
        assertEquals(listOf(SETTINGS_LABEL), focusedTexts())
        // Down does nothing.
        press(Key.DirectionDown)
        assertEquals(listOf(SETTINGS_LABEL), focusedTexts())

        press(Key.DirectionUp)
        assertEquals(listOf("Row 2"), focusedTexts())
    }

    @Test
    fun focusRequestsFromCodeAreNotRedirectedToTheSavedRow() {
        listOf(FocusDirection.Enter, FocusDirection.Down).forEach { direction ->
            val target = FocusRequester()
            withHome(rows = 3, codeFocusTarget = target) {
                focusRow("Row 2")
                // The content saves the row as focus leaves it.
                press(Key.DirectionLeft)
                assertInRail()
                press(Key.DirectionRight)
                assertEquals(listOf("Row 2"), focusedTexts())
                press(Key.DirectionLeft)
                assertInRail()

                target.requestFocus(direction)
                frame()
                assertEquals(listOf(CODE_TARGET), focusedTexts(), "$direction")
            }
        }
    }

    @Test
    fun mouseClicksFocusWhatTheyHit() = withHome(rows = 3) {
        // A click on the rail switches tabs.
        click(scene.nodeWithText(OTHER_LABEL).boundsInRoot.center)
        assertEquals(InputMode.Touch, inputMode)
        assertTrue(hasText(OTHER_ROW), "The tab did not switch")

        // The keyboard leaves the content from the first field.
        repeat(TABS_TO_REACH_A_ROW) {
            if (focusedEditableText() != FIRST_FIELD) press(Key.Tab)
        }
        assertEquals(FIRST_FIELD, focusedEditableText())
        press(Key.Tab, shift = true)
        assertInRail()

        // A click on the second field focuses it. The content
        // restores focus in keyboard input mode only, so the
        // first field does not get it on the way.
        firstFieldFocusGains = 0
        val secondField = scene.mergedNodes()
            .first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == SECOND_FIELD }
        click(secondField.boundsInRoot.center)
        assertEquals(InputMode.Touch, inputMode)
        assertEquals(SECOND_FIELD, focusedEditableText())
        assertEquals(0, firstFieldFocusGains, "The first field took focus on the way")
    }
}

private const val VAULT_LABEL = "Vault"

/** The item pane takes a part of the content, so the vault list shows its filters only from here. */
private const val VAULT_FILTERS_WIDTH = 1600
private const val SETTINGS_LABEL = "Settings"
private const val OTHER_LABEL = "Other"
private const val OTHER_ROW = "Other row"

/** The English text of `pref_item_allow_screenshots_badge`. */
private const val SCREENSHOTS_BANNER = "Screenshots are allowed, tap to disable"
private const val FIRST_FIELD = "First field"
private const val CODE_TARGET = "Code target"
private const val SECOND_FIELD = "Second field"

/** Counts the times the first field gains focus. Main thread only. */
private var firstFieldFocusGains = 0

/** The menu button, two items and the sync status. */
private const val RAIL_FOCUSABLES = 4

/** A collapsed rail is 96 dp wide, and the scene has a density of 1. */
private const val RAIL_MAX_WIDTH = 100f

/** Below the first screen of rows. */
private const val FAR_ROW = 25

private fun withHome(
    rows: Int,
    width: Int = NavigationComposeHarness.WIDE_WIDTH,
    height: Int = NavigationComposeHarness.WIDE_HEIGHT,
    allowScreenshots: AllowScreenshots = AllowScreenshots.FULL,
    codeFocusTarget: FocusRequester? = null,
    // Starts on a Vault tab with the [rows], and filter chips with the [vaultChips] titles.
    vault: Boolean = false,
    vaultChips: List<String> = emptyList(),
    block: NavigationComposeHarness.() -> Unit,
) = withNavigationHarness(modules = listOf(homeTestModule(allowScreenshots))) {
    val vaultItem = HomeNavigationItem(
        key = "vault",
        spec = NavItemSpec(NavItemRef.BuiltIn("vault")),
        testTag = null,
        route = VaultTabRoute(rows, vaultChips),
        stackId = "vault",
        icon = Icons.Outlined.Home,
        iconSelected = Icons.Filled.Home,
        label = TextHolder.Value(VAULT_LABEL),
    )
    val settings = HomeNavigationItem(
        key = "settings",
        spec = NavItemSpec(NavItemRef.BuiltIn("settings")),
        testTag = null,
        route = SettingsTabRoute(rows, codeFocusTarget),
        stackId = "settings",
        icon = Icons.Outlined.Settings,
        iconSelected = Icons.Filled.Settings,
        label = TextHolder.Value(SETTINGS_LABEL),
    )
    val other = HomeNavigationItem(
        key = "other",
        spec = NavItemSpec(NavItemRef.BuiltIn("other")),
        testTag = null,
        route = OtherTabRoute,
        stackId = "other",
        icon = Icons.Outlined.Home,
        iconSelected = Icons.Filled.Home,
        label = TextHolder.Value(OTHER_LABEL),
    )
    val home = HomeHostRoute(
        routes = if (vault) {
            persistentListOf(vaultItem, settings, other)
        } else {
            persistentListOf(settings, other)
        },
    )
    start(home, width, height)
    block()
}

private fun homeTestModule(
    allowScreenshots: AllowScreenshots,
) = module {
    single<GetNavLabel> {
        object : GetNavLabel {
            private val flow = MutableStateFlow(true)

            override fun invoke(): StateFlow<Boolean> = flow
        }
    }
    single<GetAccountStatus> {
        object : GetAccountStatus {
            override fun invoke(): Flow<DAccountStatus> = flowOf(DAccountStatus())
        }
    }
    single<GetAllowScreenshots> {
        object : GetAllowScreenshots {
            override fun invoke(): Flow<AllowScreenshots> = flowOf(allowScreenshots)
        }
    }
    single<PutAllowScreenshots> {
        object : PutAllowScreenshots {
            override fun invoke(value: AllowScreenshots): IO<Unit> = ioUnit()
        }
    }
}

/** The real home content, with its own router like [HomeScreen]. */
private class HomeHostRoute(
    private val routes: ImmutableList<HomeNavigationItem>,
) : Route {
    @Composable
    override fun Content() {
        WizardPreview {
            val initial = routes.first()
            NavigationRouter(
                id = "home",
                initial = initial.route,
                initialStackId = initial.stackId,
            ) { backStack ->
                HomeScreenContent(
                    backStack = backStack,
                    routes = routes,
                )
            }
        }
    }
}

/**
 * The Settings tab, with a list of [rows]. The test focuses the
 * [codeFocusTarget], a button in the bottom end corner, from code.
 */
private class SettingsTabRoute(
    private val rows: Int,
    private val codeFocusTarget: FocusRequester?,
) : Route {
    @Composable
    override fun Content() {
        SettingsScreen(
            initial = SettingsListRoute(rows, codeFocusTarget),
        )
    }
}

private class SettingsListRoute(
    private val rows: Int,
    private val codeFocusTarget: FocusRequester?,
) : Route {
    @Composable
    override fun Content() {
        val items = remember(rows) {
            (1..rows).map { i ->
                SettingsItem(
                    id = "row$i",
                    title = TextHolder.Value("Row $i"),
                    text = TextHolder.Value(""),
                    route = DetailRoute,
                )
            }
        }
        // The button is after the rows, and not to the left of them.
        Box {
            SettingListScreenContent(
                accountsState = remember { mutableStateOf(AccountListState()) },
                items = items,
            )
            if (codeFocusTarget != null) {
                TextButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .focusRequester(codeFocusTarget),
                    onClick = {},
                ) {
                    Text(CODE_TARGET)
                }
            }
        }
    }
}

/** The Vault tab, with a router like VaultScreen, a list of [rows] and filter [chips]. */
private class VaultTabRoute(
    private val rows: Int,
    private val chips: List<String>,
) : Route {
    @Composable
    override fun Content() {
        NavigationRouter(id = "vault", initial = VaultListTabRoute(rows, chips)) { backStack ->
            TwoPaneNavigationContent(backStack)
        }
    }
}

private class VaultListTabRoute(
    private val rows: Int,
    private val chips: List<String>,
) : Route {
    @Composable
    override fun Content() {
        val state = remember(rows, chips) {
            VaultListState(
                query = TextFieldModel(text = "", onChange = {}),
                filters = chips.map { title -> typeChip(title) },
                content = VaultListState.Content.Items(
                    list = (1..rows).map { i ->
                        VaultItem2.Button(id = "vault$i", title = "Vault row $i", onClick = {})
                    },
                ),
            )
        }
        VaultListScreenContent(
            args = VaultRoute.Args(),
            state = state,
            openItemId = { null },
        )
    }
}

/** Takes no focus when it opens. */
private object DetailRoute : Route {
    @Composable
    override fun Content() {
        Text("Detail")
    }
}

/** Text fields take focus on a click, in any input mode. */
private object OtherTabRoute : Route {
    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            listOf(FIRST_FIELD, SECOND_FIELD).forEach { text ->
                BasicTextField(
                    modifier = Modifier
                        .padding(16.dp)
                        .width(200.dp)
                        .onFocusChanged {
                            if (it.isFocused && text == FIRST_FIELD) firstFieldFocusGains++
                        },
                    value = text,
                    onValueChange = {},
                )
            }
            TextButton(onClick = {}) {
                Text(OTHER_ROW)
            }
        }
    }
}

private fun NavigationComposeHarness.focusedTexts(): List<String> = scene.focusedTexts()

/** Tabs from the start of the window to the row with the [text]. */
private fun NavigationComposeHarness.focusRow(text: String) {
    repeat(TABS_TO_REACH_A_ROW) {
        if (focusedTexts() != listOf("Row 1")) press(Key.Tab)
    }
    assertEquals(listOf("Row 1"), focusedTexts())
    repeat(FAR_ROW) {
        if (focusedTexts() != listOf(text)) press(Key.DirectionDown)
    }
    assertEquals(listOf(text), focusedTexts())
}

/** Moves up the rail to the item with the [label]. */
private fun NavigationComposeHarness.focusRailItem(label: String) {
    repeat(RAIL_FOCUSABLES) {
        if (focusedTexts() != listOf(label)) press(Key.DirectionUp)
    }
    repeat(RAIL_FOCUSABLES) {
        if (focusedTexts() != listOf(label)) press(Key.DirectionDown)
    }
    assertEquals(listOf(label), focusedTexts())
}

private const val TABS_TO_REACH_A_ROW = 12

private fun NavigationComposeHarness.focusedNode(): SemanticsNode = scene.focusedNode()

private fun NavigationComposeHarness.assertInRail() {
    val bounds = focusedNode().boundsInRoot
    assertTrue(bounds.right <= RAIL_MAX_WIDTH, "Not in the rail: $bounds, ${focusedTexts()}")
}

private fun NavigationComposeHarness.assertInBar() {
    val bounds = focusedNode().boundsInRoot
    val height = scene.semanticsOwners.first().rootSemanticsNode.size.height
    assertTrue(bounds.top >= height - BAR_MAX_HEIGHT, "Not in the bar: $bounds, ${focusedTexts()}")
}

private const val BAR_MAX_HEIGHT = 100

private fun NavigationComposeHarness.focusedEditableText(): String? = scene
    .focusedOrNull()
    ?.getOrNull(SemanticsProperties.EditableText)
    ?.text
