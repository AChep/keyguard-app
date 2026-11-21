package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutStatus
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.ListFocus
import com.artemchep.keyguard.ui.focus.enterAt
import com.artemchep.keyguard.ui.focus.rememberListFocus
import com.artemchep.keyguard.ui.screenMaxWidth
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun KeyboardShortcutsSettingsScreen() {
    val state = produceKeyboardShortcutsSettingsState()
    KeyboardShortcutsSettingsContent(state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardShortcutsSettingsContent(state: Loadable<KeyboardShortcutsSettingsState>) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val listState = rememberLazyListState()
    // Busy disables every control, so the focused one loses focus.
    // No keys while busy, so the list restores its row once it ends.
    val listKeys = remember(state) {
        val value = (state as? Loadable.Ok)?.value
        if (value == null || value.busy) {
            emptyList()
        } else {
            value.items.map { it.action.name }
        }
    }
    val focus = rememberListFocus(
        listState = listState,
        listKeys = listKeys,
        focusFirstOnEnter = true,
    )
    ScaffoldLazyColumn(
        modifier =
            Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = { Text(stringResource(Res.string.keyboard_shortcuts_title)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
        listModifier = focus.listModifier,
        listState = listState,
    ) {
        if (state is Loadable.Ok) {
            keyboardShortcutItems(state.value, focus)
        } else {
            item("loading") {
                KeyguardLoadingIndicator(
                    modifier =
                        Modifier
                            .padding(Dimens.contentPadding),
                )
            }
        }
    }
}

private fun LazyListScope.keyboardShortcutItems(
    state: KeyboardShortcutsSettingsState,
    focus: ListFocus,
) {
    itemsIndexed(
        items = state.items,
        key = { _, item -> item.action.name },
    ) { index, item ->
        // The same max width as a ScaffoldColumn screen.
        Column(
            modifier =
                Modifier
                    .widthIn(max = screenMaxWidth)
                    .then(focus.target(item.action.name)),
        ) {
            CompositionLocalProvider(
                LocalSettingItemShape provides getShapeState(state.items, index) { _, _ -> true },
            ) {
                KeyboardShortcutItem(item, state)
            }
        }
    }
}

@Composable
private fun KeyboardShortcutItem(
    item: KeyboardShortcutsSettingsState.Item,
    state: KeyboardShortcutsSettingsState,
) {
    val title = shortcutActionTitle(item.action)
    val bindingLabel = stringResource(Res.string.keyboard_shortcuts_binding_label, title)
    val binding = item.config.resolve(item.action, CurrentPlatform)
    val bindingText = remember(binding) { binding.displayText() }
    val bindingRequester = remember { FocusRequester() }
    LocalSettingPaneComponents.current.KgSwitch(
        title = { Text(title) },
        text = { KeyboardShortcutStatus(item) },
        checked = item.config.enabled,
        onCheckedChange = item.onEnabledChange.takeUnless { state.busy },
        footer = {
            Row(
                modifier =
                    Modifier
                        // Up and Down pick the button nearest to the middle
                        // of the row they come from, so redirect them to the
                        // binding. Right then moves to Reset.
                        .enterAt(bindingRequester, listOf(FocusDirection.Down, FocusDirection.Up))
                        .focusGroup()
                        .padding(
                            bottom = Dimens.verticalPadding,
                            start = Dimens.horizontalPadding,
                            end = Dimens.horizontalPadding,
                        ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalButton(
                    onClick = item.onEdit,
                    enabled = !state.busy,
                    modifier =
                        Modifier
                            .focusRequester(bindingRequester)
                            .semantics {
                                contentDescription = bindingLabel
                                stateDescription = bindingText
                            },
                ) {
                    Text(bindingText)
                }
                TextButton(onClick = item.onReset, enabled = !state.busy && item.config.binding != null) {
                    Text(stringResource(Res.string.reset))
                }
            }
        },
    )
}

@Composable
private fun KeyboardShortcutStatus(item: KeyboardShortcutsSettingsState.Item) {
    val status =
        when (item.status) {
            GlobalShortcutStatus.Active -> Res.string.keyboard_shortcuts_status_active
            GlobalShortcutStatus.Disabled -> Res.string.disabled
            GlobalShortcutStatus.Recording -> Res.string.keyboard_shortcuts_status_recording
            is GlobalShortcutStatus.Failed -> null
        }
    val failure = item.error ?: (item.status as? GlobalShortcutStatus.Failed)?.error
    Column {
        status?.let { Text(stringResource(it)) }
        failure?.let { Text(shortcutErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun shortcutActionTitle(action: GlobalShortcutAction): String =
    stringResource(
        when (action) {
            GlobalShortcutAction.QuickSearch -> Res.string.keyboard_shortcuts_action_quick_search
            GlobalShortcutAction.LockVault -> Res.string.vault_action_lock_vault_title
        },
    )

@Composable
internal fun shortcutErrorText(error: GlobalShortcutError): String =
    when (error) {
        is GlobalShortcutError.Duplicate -> {
            val title = shortcutActionTitle(error.action)
            stringResource(Res.string.keyboard_shortcuts_error_duplicate, title)
        }

        GlobalShortcutError.Invalid -> stringResource(Res.string.keyboard_shortcuts_error_invalid)

        GlobalShortcutError.UnsupportedPlatform ->
            stringResource(Res.string.keyboard_shortcuts_error_unsupported_platform)

        GlobalShortcutError.UnsupportedSession ->
            stringResource(Res.string.keyboard_shortcuts_error_unsupported_session)

        GlobalShortcutError.Unavailable -> stringResource(Res.string.keyboard_shortcuts_error_unavailable)

        GlobalShortcutError.Registration -> stringResource(Res.string.keyboard_shortcuts_error_registration)

        GlobalShortcutError.Persistence -> stringResource(Res.string.keyboard_shortcuts_error_persistence)

        GlobalShortcutError.PersistenceRestoration ->
            stringResource(Res.string.keyboard_shortcuts_error_persistence_restoration)
    }
