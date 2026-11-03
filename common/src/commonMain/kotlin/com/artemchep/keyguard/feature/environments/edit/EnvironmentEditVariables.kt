package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TextFields
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.environments.EnvironmentEditorState
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentResolver
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.copy_value
import com.artemchep.keyguard.res.environments_unset
import com.artemchep.keyguard.res.environments_variable_type_reference
import com.artemchep.keyguard.res.environments_variable_type_value
import com.artemchep.keyguard.res.list_move_down
import com.artemchep.keyguard.res.list_move_up
import com.artemchep.keyguard.res.remove
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

internal class EnvironmentVariableStrings(
    val nameHint: String,
    val valueHint: String,
    val unsetHint: String,
    val nameInvalid: String,
    val nameDuplicate: String,
    val password: String,
)

internal class EnvironmentVariableCallbacks(
    val onCopy: (Int) -> Unit,
    val onSourceChange: (Int, EnvironmentValueSource) -> Unit,
    val onPickReference: (Int) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onNameChange: (Int, String) -> Unit,
    val onValueChange: (Int, String) -> Unit,
    val onReveal: (Int) -> Unit,
)

/**
 * @param referenceItems The items the variables can reference, keyed by
 *   their canonical provider id.
 */
@Suppress("LongMethod", "LongParameterList")
internal fun createVariableStates(
    draft: EnvironmentEditorState,
    canEdit: Boolean,
    submitAttempted: Boolean,
    revision: Int,
    referenceItems: Map<String, VaultItemPresentation>,
    strings: EnvironmentVariableStrings,
    callbacks: EnvironmentVariableCallbacks,
): ImmutableList<EnvironmentEditState.Variable> {
    val variables = draft.definition.variables
    val nameCounts = variables
        .groupingBy { it.name }
        .eachCount()
    return variables
        .mapIndexed { index, variable ->
            val source = variable.source
            val literal = source as? EnvironmentValueSource.Literal
            val type = source.editType()
            val reference = (source as? EnvironmentValueSource.Reference)?.let { reference ->
                createReferenceState(
                    reference = reference,
                    item = referenceItems[reference.cipherId],
                    password = strings.password,
                    onClick = {
                        callbacks.onPickReference(index)
                    }.takeIf { canEdit },
                )
            }
            EnvironmentEditState.Variable(
                key = "variable.$index",
                type = type,
                typeActions = if (draft.editable) {
                    createVariableTypeActions(
                        index = index,
                        type = type,
                        canEdit = canEdit,
                        callbacks = callbacks,
                    )
                } else {
                    persistentListOf()
                },
                name = TextFieldModel(
                    text = variable.name,
                    textRevision = revision,
                    hint = strings.nameHint,
                    error = variableNameError(
                        name = variable.name,
                        duplicate = (nameCounts[variable.name] ?: 0) > 1,
                        submitAttempted = submitAttempted,
                        strings = strings,
                    ),
                    onChange = if (canEdit) {
                        // lambda
                        {
                            callbacks.onNameChange(index, it)
                        }
                    } else {
                        null
                    },
                ),
                value = TextFieldModel(
                    text = literal?.value.orEmpty(),
                    textRevision = revision,
                    // An unset variable is distinct from an empty
                    // value, so it reads as such.
                    hint = if (type == EnvironmentEditState.Type.Unset) strings.unsetHint else strings.valueHint,
                    onChange = if (canEdit && literal != null) {
                        // lambda
                        {
                            callbacks.onValueChange(index, it)
                        }
                    } else {
                        null
                    },
                ),
                reference = reference,
                concealed = literal != null,
                revealed = index in draft.revealed,
                onRevealChange = if (literal != null) {
                    // lambda
                    {
                        callbacks.onReveal(index)
                    }
                } else {
                    null
                },
                actions = createVariableActions(
                    index = index,
                    count = variables.size,
                    literal = literal != null,
                    editable = draft.editable,
                    canEdit = canEdit,
                    callbacks = callbacks,
                ),
            )
        }
        .mapListShape()
        .toPersistentList()
}

private fun EnvironmentValueSource.editType() = when (this) {
    is EnvironmentValueSource.Literal -> EnvironmentEditState.Type.Value
    is EnvironmentValueSource.Reference -> EnvironmentEditState.Type.Reference
    EnvironmentValueSource.Unset -> EnvironmentEditState.Type.Unset
    is EnvironmentValueSource.Unsupported -> EnvironmentEditState.Type.Unsupported
}

private fun createReferenceState(
    reference: EnvironmentValueSource.Reference,
    item: VaultItemPresentation?,
    password: String,
    onClick: (() -> Unit)?,
) = EnvironmentEditState.Reference(
    item = item,
    field = when (val field = reference.field) {
        EnvironmentFieldReference.Password -> password
        is EnvironmentFieldReference.Custom -> field.name
    },
    onClick = onClick,
)

private fun variableNameError(
    name: String,
    duplicate: Boolean,
    submitAttempted: Boolean,
    strings: EnvironmentVariableStrings,
): String? = when {
    // A freshly added variable is not an error
    // until the user tries to save it.
    name.isEmpty() && !submitAttempted -> null
    !EnvironmentResolver.isValidName(name) -> strings.nameInvalid
    duplicate -> strings.nameDuplicate
    else -> null
}

/** Value, Reference and Unset; picking a reference opens the field picker. */
private fun createVariableTypeActions(
    index: Int,
    type: EnvironmentEditState.Type,
    canEdit: Boolean,
    callbacks: EnvironmentVariableCallbacks,
): ImmutableList<ContextItem> {
    fun onClick(block: () -> Unit): (() -> Unit)? = block.takeIf { canEdit }

    return buildContextItems {
        this += FlatItemAction(
            id = "environment.variable.$index.type.value",
            icon = Icons.Outlined.TextFields,
            title = Res.string.environments_variable_type_value.wrap(),
            selected = type == EnvironmentEditState.Type.Value,
            onClick = onClick {
                if (type != EnvironmentEditState.Type.Value) {
                    callbacks.onSourceChange(index, EnvironmentValueSource.Literal(""))
                }
            },
        )
        this += FlatItemAction(
            id = "environment.variable.$index.type.reference",
            icon = Icons.Outlined.Link,
            title = Res.string.environments_variable_type_reference.wrap(),
            selected = type == EnvironmentEditState.Type.Reference,
            onClick = onClick {
                callbacks.onPickReference(index)
            },
        )
        this += FlatItemAction(
            id = "environment.variable.$index.type.unset",
            icon = Icons.Outlined.Block,
            title = Res.string.environments_unset.wrap(),
            selected = type == EnvironmentEditState.Type.Unset,
            onClick = onClick {
                callbacks.onSourceChange(index, EnvironmentValueSource.Unset)
            },
        )
    }
}

@Suppress("LongMethod", "LongParameterList")
private fun createVariableActions(
    index: Int,
    count: Int,
    literal: Boolean,
    editable: Boolean,
    canEdit: Boolean,
    callbacks: EnvironmentVariableCallbacks,
): ImmutableList<ContextItem> {
    fun onClick(block: () -> Unit): (() -> Unit)? = block.takeIf { canEdit }

    return buildContextItems {
        section {
            // Only values can be copied: a reference never
            // reveals the value of the item it points at.
            if (literal) {
                this += FlatItemAction(
                    id = "environment.variable.$index.copy",
                    icon = Icons.Outlined.ContentCopy,
                    title = Res.string.copy_value.wrap(),
                    type = FlatItemAction.Type.COPY,
                    onClick = {
                        callbacks.onCopy(index)
                    },
                )
            }
        }
        section {
            if (editable && index > 0) {
                this += FlatItemAction(
                    id = "environment.variable.$index.moveUp",
                    icon = Icons.Outlined.ArrowUpward,
                    title = Res.string.list_move_up.wrap(),
                    onClick = onClick {
                        callbacks.onMove(index, index - 1)
                    },
                )
            }
            if (editable && index < count - 1) {
                this += FlatItemAction(
                    id = "environment.variable.$index.moveDown",
                    icon = Icons.Outlined.ArrowDownward,
                    title = Res.string.list_move_down.wrap(),
                    onClick = onClick {
                        callbacks.onMove(index, index + 1)
                    },
                )
            }
        }
        section {
            if (editable) {
                this += FlatItemAction(
                    id = "environment.variable.$index.remove",
                    icon = Icons.Outlined.DeleteForever,
                    title = Res.string.remove.wrap(),
                    danger = true,
                    onClick = onClick {
                        callbacks.onRemove(index)
                    },
                )
            }
        }
    }
}
