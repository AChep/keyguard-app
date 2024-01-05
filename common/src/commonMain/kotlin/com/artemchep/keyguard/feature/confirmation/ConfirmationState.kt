package com.artemchep.keyguard.feature.confirmation

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2

data class ConfirmationState(
    val items: Loadable<List<Item>> = Loadable.Loading,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    sealed interface Item {
        val key: String
        val value: Any?
        val valid: Boolean

        data class BooleanItem(
            override val key: String,
            override val value: Boolean,
            val title: String,
            val onChange: (Boolean) -> Unit,
        ) : Item {
            override val valid: Boolean
                get() = true
        }

        data class StringItem(
            override val key: String,
            override val value: String,
            val state: TextFieldModel2,
            val title: String,
            val description: String?,
            val sensitive: Boolean,
            val monospace: Boolean,
            val password: Boolean,
            val generator: Generator?,
        ) : Item {
            override val valid: Boolean
                get() = state.error == null

            enum class Generator {
                Username,
                Password,
            }
        }

        data class EnumItem(
            override val key: String,
            override val value: String,
            val items: List<Item>,
            val doc: Doc? = null,
        ) : Item {
            override val valid: Boolean
                get() = value.isNotEmpty()

            data class Item(
                val key: String,
                val icon: ImageVector? = null,
                val title: String,
                val text: String? = null,
                val selected: Boolean,
                val onClick: (() -> Unit)? = null,
            )

            data class Doc(
                val text: String,
                val onLearnMore: (() -> Unit)?,
            )
        }
    }
}
