@file:JvmName("GeneratorStateUtils")

package com.artemchep.keyguard.feature.generator

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.auth.common.IntFieldModel
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Stable
data class GeneratorState(
    val onOpenHistory: () -> Unit,
    val options: ImmutableList<ContextItem>,
    val typeState: StateFlow<Type>,
    val valueState: StateFlow<Value?>,
    val filterState: StateFlow<Filter>,
) {
    companion object;

    @Immutable
    data class Type(
        val title: String,
        val items: ImmutableList<ContextItem>,
    )

    @Immutable
    data class Filter(
        val tip: Tip?,
        val length: Length?,
        val items: ImmutableList<Item>,
    ) {
        companion object;

        @Immutable
        data class Length(
            val value: Int,
            val min: Int,
            val max: Int,
            val onChange: ((Int) -> Unit)? = null,
        )

        sealed interface Item {
            val key: String

            @Immutable
            data class Switch(
                override val key: String,
                val title: String,
                val text: String? = null,
                val model: SwitchFieldModel,
                val counter: IntFieldModel? = null,
            ) : Item

            @Immutable
            data class Text(
                override val key: String,
                val title: String,
                val model: TextFieldModel2,
            ) : Item

            @Immutable
            data class Enum(
                override val key: String,
                val title: String,
                val model: Model,
            ) : Item {
                @Immutable
                data class Model(
                    val value: String,
                    val dropdown: ImmutableList<ContextItem>,
                )
            }

            @Immutable
            data class Section(
                override val key: String,
                val text: String? = null,
            ) : Item
        }

        @Immutable
        data class Tip(
            val text: String,
            val onHide: (() -> Unit)? = null,
            val onLearnMore: (() -> Unit)? = null,
        )
    }

    @Immutable
    data class Value(
        val password: String,
        val strength: Boolean,
        val actions: ImmutableList<FlatItemAction>,
        val dropdown: ImmutableList<ContextItem>,
        val onCopy: (() -> Unit)?,
        val onRefresh: (() -> Unit)?,
    ) {
        companion object
    }
}
