package com.artemchep.keyguard.feature.gpgkey.userids

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_ids_empty
import com.artemchep.keyguard.res.gpg_user_ids_history
import com.artemchep.keyguard.ui.DialogPopup
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ProvideScaffoldLocalValues
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * Keeps the last state composed after the manager closes, so
 * the dialog can play its exit animation.
 */
@Composable
fun GpgUserIdsDialog(state: GpgUserIdsState?) {
    var lastState by remember {
        mutableStateOf(state)
    }
    state?.let {
        lastState = it
    }
    val currentState = lastState
        ?: return
    val updatedOnBack by rememberUpdatedState(state?.back?.onClick)
    DialogPopup(
        onDismissRequest = {
            updatedOnBack?.invoke()
        },
        expanded = state != null,
    ) {
        // The dialog is hosted by the item editor; render it with
        // the same local values as a navigation dialog.
        ProvideScaffoldLocalValues(
            expressive = false,
        ) {
            GpgUserIdsScreen(currentState)
        }
    }
}

@Composable
private fun GpgUserIdsScreen(state: GpgUserIdsState) {
    DialogContent(
        title = {
            Text(state.title)
        },
        content = {
            GpgUserIdsContent(state)
        },
        actions = {
            GpgUserIdActionButton(state.back)
            state.confirm?.let { action ->
                GpgUserIdActionButton(action)
            }
        },
    )
}

@Composable
private fun GpgUserIdsContent(state: GpgUserIdsState) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = state.message,
        )
        ExpandedIfNotEmpty(
            Unit.takeIf { state.busy || state.loading },
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding)
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        state.identities.forEach { identity ->
            key(identity.id) {
                GpgUserIdItem(identity)
            }
        }
        if (state.page == GpgUserIdsState.Page.List && state.identities.isEmpty() && !state.loading) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = stringResource(Res.string.gpg_user_ids_empty),
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
        GpgUserIdForm(state)
        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.add?.let { action ->
            GpgUserIdAddButton(action)
        }
        if (state.history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            GpgUserIdHistory(state.history)
        }
    }
}

@Composable
private fun GpgUserIdForm(state: GpgUserIdsState) {
    if (state.formats.isEmpty() && state.fields.isEmpty()) {
        return
    }
    if (state.identities.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
    }
    val updatedOnConfirm by rememberUpdatedState(state.confirm?.onClick)
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.formats.isNotEmpty()) {
            SegmentedButtonGroup(
                tabState = rememberUpdatedState(state.formats.firstOrNull { it.selected }),
                tabs = state.formats,
                onClick = { it.action.onClick?.invoke() },
                isTabEnabled = { it.action.onClick != null },
                modifier = Modifier
                    .fillMaxWidth(),
                weight = 1f,
            )
        }
        state.fields.forEachIndexed { index, field ->
            key(field.id) {
                val last = index == state.fields.lastIndex
                FlatTextField(
                    testTag = "field:gpg_user_id_${field.id}",
                    label = field.label,
                    value = field.value,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = if (field.kind == GpgUserIdsState.Field.Kind.Email) {
                            KeyboardType.Email
                        } else {
                            KeyboardType.Text
                        },
                        imeAction = if (last) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            updatedOnConfirm?.invoke()
                        },
                    ),
                    singleLine = true,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GpgUserIdAddButton(action: GpgUserIdsState.Action) {
    Button(
        modifier = Modifier
            .padding(top = 8.dp)
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        enabled = action.onClick != null,
        onClick = {
            action.onClick?.invoke()
        },
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        Text(action.title)
    }
}

@Composable
private fun GpgUserIdHistory(history: List<GpgUserIdsState.Identity>) {
    var expanded by rememberSaveable {
        mutableStateOf(false)
    }
    FlatItemLayout(
        content = {
            FlatItemTextContent(
                title = {
                    Text(stringResource(Res.string.gpg_user_ids_history))
                },
            )
        },
        trailing = {
            DropdownIcon(
                expanded = expanded,
            )
        },
        onClick = {
            expanded = !expanded
        },
    )
    ExpandedIfNotEmpty(
        Unit.takeIf { expanded },
    ) {
        Column {
            history.forEach { identity ->
                key(identity.id) {
                    GpgUserIdItem(identity)
                }
            }
        }
    }
}

@Composable
private fun GpgUserIdActionButton(action: GpgUserIdsState.Action) {
    TextButton(
        enabled = action.onClick != null,
        colors = if (action.destructive) {
            ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            )
        } else {
            ButtonDefaults.textButtonColors()
        },
        onClick = {
            action.onClick?.invoke()
        },
    ) {
        Text(action.title)
    }
}

@Composable
private fun GpgUserIdItem(identity: GpgUserIdsState.Identity) {
    val actions = identity.actions.map { action ->
        FlatItemAction(
            id = action.id,
            title = TextHolder.Value(action.title),
            danger = action.destructive,
            onClick = action.onClick,
        )
    }
    // Clicking a row with actions opens them in a dropdown.
    FlatDropdown(
        content = {
            FlatItemTextContent(
                title = {
                    Text(identity.value)
                },
                text = identity.status?.let { status ->
                    // composable
                    {
                        Text(status)
                    }
                },
            )
        },
        dropdown = actions,
        enabled = true,
    )
}
