package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.artemchep.keyguard.feature.add.AddButton
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.component.VaultItemReference
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_add_variable
import com.artemchep.keyguard.res.environments_variable_type
import com.artemchep.keyguard.ui.BiFlatContainer
import com.artemchep.keyguard.ui.BiFlatTextField
import com.artemchep.keyguard.ui.BiFlatTextFieldLabel
import com.artemchep.keyguard.ui.BiFlatValueHeightMin
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.rememberFieldBuffer
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EnvironmentVariableItem(
    variable: EnvironmentEditState.Variable,
) {
    val reference = variable.reference
    if (reference != null) {
        EnvironmentReferenceVariableItem(
            variable = variable,
            reference = reference,
        )
        return
    }

    val revealed = variable.revealed || !variable.concealed
    BiFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = variable.name,
        value = variable.value,
        shapeState = variable.shapeState,
        valueVisualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        valueIncognito = variable.concealed,
        leading = {
            EnvironmentVariableTypeButton(
                type = variable.type,
                actions = variable.typeActions,
            )
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                valueOrNull = variable.onRevealChange,
            ) { onRevealChange ->
                VisibilityToggle(
                    visible = variable.revealed,
                    onVisibleChange = onRevealChange,
                )
            }
            OptionsButton(
                actions = variable.actions,
            )
        },
    )
}

/**
 * A variable that references a field of another item: the name stays
 * editable, the value line shows the item and the field.
 */
@Composable
private fun EnvironmentReferenceVariableItem(
    variable: EnvironmentEditState.Variable,
    reference: EnvironmentEditState.Reference,
) {
    val label = variable.name
    val labelBuffer = rememberFieldBuffer(
        text = label.text,
        textRevision = label.textRevision,
    )
    val labelInteractionSource = remember { MutableInteractionSource() }
    val valueInteractionSource = remember { MutableInteractionSource() }
    val isError = rememberUpdatedState(label.error != null || reference.item == null)
    val hasFocusState = remember { mutableStateOf(false) }
    val isEmpty = remember(labelBuffer) { derivedStateOf { labelBuffer.value.text.isBlank() } }
    val updatedOnClick by rememberUpdatedState(reference.onClick)
    BiFlatContainer(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding)
            .onFocusChanged { state ->
                hasFocusState.value = state.hasFocus
            },
        contentModifier = Modifier
            .clickable(
                enabled = reference.onClick != null,
                indication = LocalIndication.current,
                interactionSource = valueInteractionSource,
                role = Role.Button,
            ) {
                updatedOnClick?.invoke()
            },
        shapeState = variable.shapeState,
        isError = isError,
        isFocused = hasFocusState,
        isEmpty = isEmpty,
        label = {
            BiFlatTextFieldLabel(
                label = label,
                buffer = labelBuffer,
                interactionSource = labelInteractionSource,
            )
        },
        content = {
            EnvironmentReferenceValue(
                reference = reference,
            )
        },
        leading = {
            EnvironmentVariableTypeButton(
                type = variable.type,
                actions = variable.typeActions,
            )
        },
        trailing = {
            OptionsButton(
                actions = variable.actions,
            )
        },
    )
}

@Composable
private fun EnvironmentReferenceValue(
    reference: EnvironmentEditState.Reference,
) {
    VaultItemReference(
        modifier = Modifier
            .heightIn(min = BiFlatValueHeightMin),
        item = reference.item,
        field = reference.field,
        chevron = reference.onClick != null,
    )
}

/** Shows the type of a variable; changing it opens a menu of the types. */
@Composable
private fun EnvironmentVariableTypeButton(
    type: EnvironmentEditState.Type,
    actions: List<ContextItem>,
) {
    OptionsButton(
        enabled = actions.isNotEmpty(),
        icon = type.icon,
        contentDescription = stringResource(Res.string.environments_variable_type),
    ) {
        actions.forEach { action ->
            DropdownMenuItemFlat(
                action = action,
            )
        }
    }
}

private val EnvironmentEditState.Type.icon: ImageVector
    get() = when (this) {
        EnvironmentEditState.Type.Value -> Icons.Outlined.TextFields
        EnvironmentEditState.Type.Reference -> Icons.Outlined.Link
        EnvironmentEditState.Type.Unset -> Icons.Outlined.Block
        EnvironmentEditState.Type.Unsupported -> Icons.Outlined.ErrorOutline
    }

/** Adds a variable; asks for its type first. */
@Composable
internal fun EnvironmentAddVariableItem(
    actions: List<ContextItem>,
) {
    Column {
        AddButton(
            text = stringResource(Res.string.environments_add_variable),
            actions = actions,
            enabled = actions.isNotEmpty(),
        )
    }
}
