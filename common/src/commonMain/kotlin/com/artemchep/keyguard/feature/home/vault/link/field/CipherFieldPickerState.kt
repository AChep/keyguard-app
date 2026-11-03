package com.artemchep.keyguard.feature.home.vault.link.field

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkPickerState
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation

@Immutable
data class CipherFieldPickerState(
    val query: TextFieldModel = TextFieldModel.empty,
    val step: Step = Step.Items(emptyList()),
    /** Returns to the items, `null` on the first step. */
    val onBack: (() -> Unit)? = null,
    val onDeny: (() -> Unit)? = null,
) {
    @Immutable
    sealed interface Step {
        data class Items(
            val items: List<CipherLinkPickerState.Item>,
        ) : Step

        data class Fields(
            val item: VaultItemPresentation,
            val fields: List<Field>,
        ) : Step
    }

    @Immutable
    data class Field(
        val id: String,
        val kind: Kind,
        val title: String,
        val text: String,
        /** Picks the field, `null` if it can not be referenced. */
        val onClick: (() -> Unit)?,
        val shapeState: Int = ShapeState.ALL,
    ) : GroupableShapeItem<Field> {
        enum class Kind {
            Password,
            Custom,
        }

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
