package com.artemchep.keyguard.feature.auth.common

import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Owns the canonical [TextCell] of a single text field. Producers derive
 * the field's [TextFieldModel] from [sink] (usually via [TextFieldModel.of]
 * with a validation flow) and route writes through the two paths:
 * [onChange] for user edits, [setText] for programmatic writes.
 */
class TextFieldHandle(
    val sink: MutableStateFlow<TextCell>,
    /**
     * The field's stable identity, surfaced into every [TextFieldModel] this
     * handle produces (see [toModel] / [modelFlow] / [TextFieldModel.of]).
     * Defaults to empty for ad-hoc handles that nothing needs to address;
     * [textFieldHandle] seeds it with the field's persistence key.
     */
    val id: String = "",
) {
    /** Reflects a user edit; keeps the text revision. */
    fun onChange(text: String) {
        sink.update { it.copy(text = text) }
    }

    /**
     * Writes the text as a command (clear, prefill, insert); bumps the
     * revision so UI edge buffers adopt the new text unconditionally.
     */
    fun setText(text: String) {
        sink.update { TextCell(text = text, revision = it.revision + 1) }
    }
}

/**
 * Builds a [TextFieldModel] from the handle's current cell. The text and
 * the revision are read atomically from the sink's value; auxiliary data
 * (error, hint) may lag the cell by one emission, which is harmless.
 */
fun TextFieldHandle.toModel(
    hint: String? = null,
    error: String? = null,
): TextFieldModel {
    val cell = sink.value
    return TextFieldModel(
        id = id,
        text = cell.text,
        textRevision = cell.revision,
        hint = hint,
        error = error,
        onChange = this::onChange,
        onSetText = this::setText,
    )
}

/**
 * Derives the field's [TextFieldModel] flow with single-pass validation:
 * one cell emission produces exactly one model. Prefer this over the
 * [Flow]-joining overload — combining a cell flow with a validation flow
 * derived from it makes every keystroke emit twice (once with a stale
 * error).
 */
fun TextFieldHandle.modelFlow(
    hint: String? = null,
    validate: suspend (String) -> Validated<String>,
): Flow<TextFieldModel> = sink.map { cell ->
    TextFieldModel.of(
        cell = cell,
        handle = this,
        validated = validate(cell.text),
        hint = hint,
    )
}

/**
 * Derives the field's [TextFieldModel] flow by joining the canonical cell
 * with its validation. Text and revision always come from the same cell
 * emission (atomic); a transiently lagging [validatedFlow] can only show
 * a stale error for one emission, never stale text.
 */
fun TextFieldHandle.modelFlow(
    validatedFlow: Flow<Validated<String>>,
    hint: String? = null,
): Flow<TextFieldModel> = combine(
    sink,
    validatedFlow,
) { cell, validated ->
    TextFieldModel.of(
        cell = cell,
        handle = this,
        validated = validated,
        hint = hint,
    )
}

/**
 * Creates a [TextFieldHandle] whose text is persisted under [key].
 * Only the text is persisted; the revision is a session-local command
 * counter and restores as zero.
 */
fun RememberStateFlowScope.textFieldHandle(
    key: String,
    initial: String = "",
    storage: PersistedStorage = PersistedStorage.InMemory,
): TextFieldHandle {
    val sink = mutablePersistedFlow(
        key,
        storage = storage,
        serialize = { _, cell: TextCell -> cell.text },
        deserialize = { _, text: String -> TextCell(text) },
    ) { TextCell(text = initial) }
    return TextFieldHandle(sink = sink, id = key)
}
