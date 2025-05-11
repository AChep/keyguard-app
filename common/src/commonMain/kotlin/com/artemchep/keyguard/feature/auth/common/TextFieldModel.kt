package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import arrow.optics.optics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

@Stable
@optics
data class TextFieldModel2(
    val state: MutableState<String>,
    val text: String = state.value,
    val hint: String? = null,
    val error: String? = null,
    val vl: Vl? = null,
    val autocompleteOptions: ImmutableList<String> = persistentListOf(),
    val focusFlow: Flow<Unit>? = null,
    val onChange: ((String) -> Unit)? = null,
) {
    companion object {
        val empty = TextFieldModel2(
            state = mutableStateOf(""),
        )

        fun of(
            state: MutableState<String>,
            validated: Validated<String>,
            hint: String? = null,
            onChange: ((String) -> Unit)? = state::value::set,
        ) = TextFieldModel2(
            state = state,
            text = validated.model,
            hint = hint,
            error = (validated as? Validated.Failure)?.error
                ?.takeUnless { validated.model.isEmpty() },
            onChange = onChange,
        )
    }

    data class Vl(
        val type: Type,
        val text: String,
    ) {
        enum class Type {
            SUCCESS,
            INFO,
            WARNING,
            ERROR,
        }
    }
}
