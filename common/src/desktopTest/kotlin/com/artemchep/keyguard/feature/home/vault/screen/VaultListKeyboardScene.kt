@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.ResponsiveLayout
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.quicksearch.createSecret
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouter
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.click
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.feature.navigation.twoPaneHost
import com.artemchep.keyguard.feature.navigation.withNavigationHarness
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.MutableStateFlow

// The vault list of the keyboard scene tests, see withVaultList.

internal const val FIRST_ROW = "Alpha"

internal const val SECOND_ROW = "Beta"

internal const val FIELD_TITLE = "Username"

internal const val FIELD_VALUE = "alice"

internal const val RAIL = "Rail"

/** Opens the item with an id, the way the vault list opens an item. */
internal typealias OpenItem = (itemId: String) -> Unit

/** Runs the [block] on a wide and on a narrow vault list, see [withVaultList]. */
internal fun forEachVaultListSize(
    query: String = "",
    focusFirstField: Boolean = true,
    fabs: Boolean = false,
    fields: () -> List<VaultViewItem> = { DEFAULT_FIELDS },
    onEdit: (NavigationComposeHarness.() -> Unit)? = null,
    actions: NavigationComposeHarness.() -> List<FlatItemAction> = { emptyList() },
    rows: (OpenItem) -> List<VaultItem2> = DEFAULT_ROWS,
    block: NavigationComposeHarness.(size: String) -> Unit,
) = listOf(
    NavigationComposeHarness.WIDE_WIDTH to NavigationComposeHarness.WIDE_HEIGHT,
    NavigationComposeHarness.NARROW_WIDTH to NavigationComposeHarness.NARROW_HEIGHT,
).forEach { (width, height) ->
    withVaultList(
        width = width,
        height = height,
        query = query,
        focusFirstField = focusFirstField,
        fabs = fabs,
        fields = fields,
        onEdit = onEdit,
        actions = actions,
        rows = rows,
    ) {
        block("${width}x$height")
    }
}

/**
 * Runs the [block] on a vault list of the [rows] in a scene of
 * the [width] and [height]. A row opens a plain item with the
 * [fields], by default one field, which focuses its first field
 * if [focusFirstField]. The list follows the snapshot state the
 * [filters], [rows] and [revision] read, the item the state the
 * [fields] read. A new [revision] scrolls the list to the top.
 * With [rail], a [RAIL] button is on the start side of the list.
 * With [fabs], the list shows its History and New item FABs.
 * With [onEdit], the toolbar of the item has an enabled Edit button,
 * and with [actions] an options button. Edit and the actions run in
 * a click handler, so they queue a route with [queueRoute] and render
 * nothing.
 */
internal fun withVaultList(
    width: Int,
    height: Int,
    query: String = "",
    filters: () -> List<FilterItem> = { emptyList() },
    revision: () -> Int = { 0 },
    focusFirstField: Boolean = true,
    rail: Boolean = false,
    fabs: Boolean = false,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    fields: () -> List<VaultViewItem> = { DEFAULT_FIELDS },
    onEdit: (NavigationComposeHarness.() -> Unit)? = null,
    actions: NavigationComposeHarness.() -> List<FlatItemAction> = { emptyList() },
    rows: (OpenItem) -> List<VaultItem2> = DEFAULT_ROWS,
    block: NavigationComposeHarness.() -> Unit,
) = withNavigationHarness {
    val harness = this
    val edit = onEdit?.let { { it(harness) } }
    val itemActions = actions(harness)
    val route = VaultListTestRoute(rows, query, filters, revision, fabs) { itemId ->
        VaultViewTestRoute(itemId, focusFirstField, fields, edit, itemActions)
    }
    val host = if (rail) railHost(route) else twoPaneHost("vault", route)
    start(host, width, height, layoutDirection)
    block()
}

/**
 * Opens the item with the [itemId] on top of the open item, the
 * way a link in an item does. The item focuses nothing.
 */
internal fun NavigationComposeHarness.pushItem(itemId: String) {
    queueRoute(VaultViewTestRoute(itemId, focusFirstField = false, fields = { DEFAULT_FIELDS }))
    repeat(2) { frame() }
}

/** Closes the item or the screen on top of the vault list. */
internal fun NavigationComposeHarness.pop() {
    router("vault").controller.queue(NavigationIntent.Pop)
    repeat(2) { frame() }
}

/**
 * Opens the [route] on top of the vault list or the open item, once
 * the scene renders. It renders nothing, so a click handler may call it.
 */
internal fun NavigationComposeHarness.queueRoute(route: Route) {
    router("vault").controller.queue(NavigationIntent.NavigateToRoute(route))
}

/**
 * Like [twoPaneHost], with a button as tall as the scene on its start
 * side, so every row is level with it, like the home navigation rail.
 */
private fun NavigationComposeHarness.railHost(
    initial: Route,
): Route = routerHost("vault", initial) { entries ->
    Row {
        TextButton(
            modifier = Modifier
                .fillMaxHeight(),
            onClick = {},
        ) {
            Text(RAIL)
        }
        Box(
            modifier = Modifier
                .weight(1f),
        ) {
            ResponsiveLayout {
                TwoPaneNavigationContent(entries)
            }
        }
    }
}

/** A row with the [title] that opens the item with the [title] as its id. */
internal fun buttonRow(title: String, open: OpenItem) = VaultItem2.Button(
    id = title,
    title = title,
    onClick = { open(title) },
)

/** The [FIRST_ROW] and the [SECOND_ROW]. */
private val DEFAULT_ROWS: (OpenItem) -> List<VaultItem2> = { open ->
    listOf(FIRST_ROW, SECOND_ROW).map { buttonRow(it, open) }
}

/** The one action of the New item FAB, its click does nothing. */
private val NEW_ITEM_ACTION = FlatItemAction(title = TextHolder.Value("Login"))

/** The one field of an item. */
internal val DEFAULT_FIELDS = listOf(
    VaultViewItem.Value(
        id = FIELD_TITLE,
        title = FIELD_TITLE,
        value = FIELD_VALUE,
        dropdown = listOf(
            FlatItemAction(title = TextHolder.Value(FIELD_TITLE)),
        ),
    ),
)

/** Clicks the search field, so the scene is in touch input mode. */
internal fun NavigationComposeHarness.clickSearchField() {
    val field = scene.mergedNodes().first { node ->
        node.config.getOrNull(SemanticsProperties.EditableText) != null
    }
    click(field.boundsInRoot.center)
}

internal fun NavigationComposeHarness.assertSearchFieldFocused(message: String? = null) {
    assertNotNull(scene.focusedOrNull()?.getOrNull(SemanticsProperties.EditableText), message)
}

/**
 * The FABs of a list [withVaultList] shows with fabs, from the top:
 * History, then New item. They are the lowest buttons without a text,
 * unlike the [RAIL].
 */
internal fun NavigationComposeHarness.vaultFabs(): List<SemanticsNode> {
    val buttons = scene.mergedNodes()
        .filter { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                node.texts().isEmpty()
        }
    val newItem = buttons.maxBy { node -> node.boundsInRoot.bottom }
    val bounds = newItem.boundsInRoot
    // The History FAB is narrower, right above it.
    val history = buttons
        .filter { node ->
            node.boundsInRoot.bottom <= bounds.top &&
                node.boundsInRoot.left < bounds.right &&
                node.boundsInRoot.right > bounds.left
        }
        .maxBy { node -> node.boundsInRoot.bottom }
    return listOf(history, newItem)
}

/** Focuses the second row from the search field, in keyboard input mode. */
internal fun NavigationComposeHarness.focusSecondRow() {
    clickSearchField()
    press(Key.DirectionDown)
    press(Key.DirectionDown)
    assertEquals(listOf(SECOND_ROW), scene.focusedTexts())
}

/**
 * The vault list of the [rows], so the test needs no ciphers. A row
 * opens the [item] route the way the vault list opens an item.
 */
private class VaultListTestRoute(
    private val rows: (OpenItem) -> List<VaultItem2>,
    private val query: String,
    private val filters: () -> List<FilterItem>,
    private val revision: () -> Int,
    private val fabs: Boolean,
    private val item: (itemId: String) -> VaultViewTestRoute,
) : Route {
    @Composable
    override fun Content() {
        val entryId = LocalNavigationEntry.current.id
        val controller by rememberUpdatedState(LocalNavigationController.current)
        val stack by rememberUpdatedState(LocalNavigationRouter.current)
        val state by remember {
            val open: OpenItem = { itemId ->
                val route = item(itemId)
                val intent = NavigationIntent.Composite(
                    listOf(
                        NavigationIntent.PopById(entryId),
                        NavigationIntent.NavigateToRoute(route),
                    ),
                )
                controller.queue(intent)
            }
            derivedStateOf {
                VaultListState(
                    query = TextFieldModel(text = query, onChange = {}),
                    filters = filters(),
                    // A primary action shows the FABs.
                    primaryActions = listOf(NEW_ITEM_ACTION).takeIf { fabs }.orEmpty(),
                    content = VaultListState.Content.Items(
                        revision = VaultListState.Content.Items.Revision(id = revision()),
                        list = rows(open),
                    ),
                )
            }
        }
        WizardPreview {
            VaultListScreenContent(
                args = VaultRoute.Args(),
                state = state,
                // Like the vault list: the top item next to the list.
                openItemId = {
                    val entries = stack.value
                    val index = entries.indexOfFirst { it.id == entryId }
                    val route = entries
                        .drop(index + 1)
                        .lastOrNull { it.route is VaultViewTestRoute }
                        ?.route
                    (route as? VaultViewTestRoute)?.itemId
                },
            )
        }
    }
}

/**
 * An item with the [fields]. No fields stand for rows that did not
 * arrive yet: the Loading skeleton reads the AWT window. The toolbar
 * has an enabled Edit button with [onEdit], and an options button
 * with [actions].
 */
private class VaultViewTestRoute(
    val itemId: String,
    private val focusFirstField: Boolean,
    private val fields: () -> List<VaultViewItem>,
    private val onEdit: (() -> Unit)? = null,
    private val actions: List<FlatItemAction> = emptyList(),
) : Route {
    @Composable
    override fun Content() {
        val state by remember {
            derivedStateOf {
                VaultViewState(
                    content = VaultViewState.Content.Cipher(
                        locked = MutableStateFlow(false),
                        data = createSecret(),
                        icon = VaultItemIcon.VectorIcon(Icons.Outlined.Password),
                        synced = true,
                        onFavourite = null,
                        onEdit = onEdit,
                        actions = actions,
                        primaryAction = null,
                        items = fields(),
                    ),
                )
            }
        }
        WizardPreview {
            VaultViewScreenContent(
                state = state,
                focusFirstField = focusFirstField,
            )
        }
    }
}
