package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class EnvironmentEditState(
    val title: String,
    val actions: ImmutableList<ContextItem>,
    /**
     * The editor content, or `null` if the
     * environment failed to load.
     */
    val content: Content?,
    /**
     * Saves the environment, `null` if the environment
     * can not be edited.
     */
    val onSave: (() -> Unit)?,
    /**
     * Imports a `.env` file dropped onto the editor, `null`
     * if the environment can not be edited.
     */
    val fileDrop: FileDrop?,
    val busy: Boolean,
) {
    @Immutable
    data class FileDrop(
        val text: String,
        val onFileDrop: (FilePickerResult) -> Unit,
    )

    @Immutable
    data class Content(
        val create: Boolean,
        val note: SimpleNote?,
        /**
         * Where the environment is stored. An existing
         * environment can only move to another folder.
         */
        val ownership: AddStateOwnership,
        val name: TextFieldModel,
        val description: TextFieldModel,
        val variables: ImmutableList<Variable>,
        /** Adds a variable of the chosen type, empty if it can not be edited. */
        val addActions: ImmutableList<ContextItem>,
    )

    @Immutable
    data class Variable(
        val key: String,
        val type: Type,
        /** Changes the type, empty if the variable can not be edited. */
        val typeActions: ImmutableList<ContextItem>,
        val name: TextFieldModel,
        val value: TextFieldModel,
        /** The referenced field, if the variable is a [Type.Reference]. */
        val reference: Reference?,
        /** `true` if the value is a secret that can be revealed. */
        val concealed: Boolean,
        val revealed: Boolean,
        val onRevealChange: ((Boolean) -> Unit)?,
        val actions: ImmutableList<ContextItem>,
        val shapeState: Int = ShapeState.ALL,
    ) : GroupableShapeItem<Variable> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }

    enum class Type {
        Value,
        Reference,
        Unset,
        Unsupported,
    }

    @Immutable
    data class Reference(
        /** The referenced item, `null` if it is not available. */
        val item: VaultItemPresentation?,
        val field: String,
        /** Picks another field, `null` if the variable can not be edited. */
        val onClick: (() -> Unit)?,
    )
}
