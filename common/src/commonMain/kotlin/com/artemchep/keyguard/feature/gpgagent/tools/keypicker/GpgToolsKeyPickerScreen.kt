package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.dialog.DialogContentScope
import com.artemchep.keyguard.feature.dialog.focusFirstResultOnDown
import com.artemchep.keyguard.feature.dialog.focusOnDialogOpen
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsKeyIdentity
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.gpg_tools_no_gpg_keys
import com.artemchep.keyguard.res.gpg_tools_no_matching_keys
import com.artemchep.keyguard.res.gpg_tools_search_keys
import com.artemchep.keyguard.res.gpg_tools_select_recipients
import com.artemchep.keyguard.res.gpg_tools_select_signing_key
import com.artemchep.keyguard.res.gpg_tools_selection_done
import com.artemchep.keyguard.res.gpg_tools_sign_none
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.focus.arrowKeyFocusNavigation
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GpgToolsKeyPickerScreen(
    args: GpgToolsKeyPickerRoute.Args,
    transmitter: RouteResultTransmitter<Set<String>>,
) {
    val state = produceGpgToolsKeyPickerState(args, transmitter)
    val multiple = args.mode == GpgToolsKeyPickerRoute.Mode.Recipients
    Dialog(
        title = {
            Text(
                stringResource(
                    if (multiple) Res.string.gpg_tools_select_recipients
                    else Res.string.gpg_tools_select_signing_key,
                ),
            )
        },
        contentScrollable = false,
        content = {
            GpgToolsKeyPickerBody(state = state, multiple = multiple, hasKeys = args.keys.isNotEmpty())
        },
        actions = {
            val onDeny by rememberUpdatedState(state.onDeny)
            val onConfirm by rememberUpdatedState(state.onConfirm)
            TextButton(enabled = onDeny != null, onClick = { onDeny?.invoke() }) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(enabled = onConfirm != null, onClick = { onConfirm?.invoke() }) {
                Text(stringResource(Res.string.gpg_tools_selection_done))
            }
        },
    )
}

@Composable
internal fun DialogContentScope.GpgToolsKeyPickerBody(
    state: GpgToolsKeyPickerState,
    multiple: Boolean,
    hasKeys: Boolean,
) {
    val listState = rememberLazyListState()
    val firstResult = remember { FocusRequester() }
    val firstIndex = state.firstResultIndex
    val focusNone = firstIndex == 0 && state.onSelectNone != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = viewportMaxHeight),
    ) {
        FlatTextField(
            modifier = Modifier.padding(horizontal = Dimens.fieldHorizontalPadding),
            fieldModifier = Modifier
                .focusOnDialogOpen(state.query.onChange != null)
                .focusFirstResultOnDown(listState, firstResult, firstIndex),
            value = state.query,
            placeholder = stringResource(Res.string.gpg_tools_search_keys),
            singleLine = true,
            clearButton = true,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .arrowKeyFocusNavigation()
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 440.dp)
                .then(if (multiple) Modifier else Modifier.selectableGroup()),
        ) {
            state.onSelectNone?.let { onSelectNone ->
                item("none") {
                    NoSigningKeyRow(
                        modifier = if (focusNone) Modifier.focusRequester(firstResult) else Modifier,
                        selected = state.noneSelected,
                        onSelect = onSelectNone,
                    )
                }
            }
            items(state.items, key = { it.key.id }) { item ->
                GpgToolsKeySelectionRow(
                    modifier = if (!focusNone && item === state.items.first()) {
                        Modifier.focusRequester(firstResult)
                    } else Modifier,
                    selected = item.selected,
                    multiple = multiple,
                    onSelect = item.onSelect,
                ) {
                    GpgToolsKeyIdentity(key = item.key)
                }
            }
            if (state.items.isEmpty()) {
                item("empty") {
                    EmptyKeyList(hasKeys)
                }
            }
        }
    }
}

private val GpgToolsKeyPickerState.firstResultIndex: Int?
    get() = when {
        onSelectNone != null && query.text.isNotBlank() && items.isNotEmpty() -> 1
        onSelectNone != null || items.isNotEmpty() -> 0
        else -> null
    }

@Composable
private fun NoSigningKeyRow(modifier: Modifier, selected: Boolean, onSelect: () -> Unit) {
    GpgToolsKeySelectionRow(modifier, selected, multiple = false, onSelect = onSelect) {
        FlatItemTextContent(title = { Text(stringResource(Res.string.gpg_tools_sign_none)) })
    }
}

@Composable
private fun EmptyKeyList(hasKeys: Boolean) {
    Text(
        modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(
            if (!hasKeys) Res.string.gpg_tools_no_gpg_keys
            else Res.string.gpg_tools_no_matching_keys,
        ),
        color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
private fun GpgToolsKeySelectionRow(
    modifier: Modifier,
    selected: Boolean,
    multiple: Boolean,
    onSelect: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    FlatItemLayout(
        modifier = if (multiple) {
            modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelect() })
        } else {
            modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        },
        leading = {
            if (multiple) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                RadioButton(selected = selected, onClick = null)
            }
        },
        content = content,
        enabled = true,
    )
}
