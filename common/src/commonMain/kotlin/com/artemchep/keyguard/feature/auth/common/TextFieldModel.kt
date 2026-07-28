package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/**
 * The pure-data half of a text field: everything a renderer needs to
 * display the field, with no behavior attached. Being free of closures
 * and flows, it is a value that can be compared for equality (so SwiftUI
 * / Compose can diff it) and handed across a native bridge as-is.
 *
 * [id] is the field's stable identity within the screen that produces it:
 * it is assigned by the producer (usually the field's persistence key, see
 * [textFieldHandle]) and is what a host without access to the Kotlin
 * [onChange] closure uses to route an edit back to the right field. It is
 * empty by default for ad-hoc / read-only fields that nothing needs to
 * address.
 *
 * UI toolkits keep their own edit buffer bound to the field and reconcile
 * it against this model: the buffer adopts [text] only when [revision]
 * changes — echoes of the user's own keystrokes keep the revision and must
 * never overwrite the buffer.
 */
@Immutable
data class TextFieldState(
    val id: String = "",
    val text: String,
    val revision: Int = 0,
    val hint: String? = null,
    val error: String? = null,
    val vl: TextFieldModel.Vl? = null,
    val autocompleteOptions: ImmutableList<String> = persistentListOf(),
    /** Whether the field accepts edits; mirrors `onChange != null`. */
    val editable: Boolean = true,
)

/**
 * A framework-agnostic text field: the pure-data [state] plus the behavior
 * (closures and flows) that cannot cross a value boundary. UI toolkits keep
 * their own edit buffer bound to the field and reconcile it against [state]:
 * keystrokes go up via [onChange], and the buffer adopts [state]'s text only
 * when its revision changes — echoes of the user's own keystrokes keep the
 * revision and must never overwrite the buffer.
 *
 * Programmatic writes (clear, prefill, insert) go through [onSetText],
 * which bumps the revision so the buffer adopts them unconditionally,
 * even while the field is focused.
 *
 * The flat accessors ([text], [hint], …) delegate to [state] so existing
 * call sites keep reading the model directly.
 */
@Immutable
data class TextFieldModel(
    val state: TextFieldState,
    /** Reflects a user edit; keeps the text revision. */
    val onChange: ((String) -> Unit)? = null,
    /** Writes the text as a command; bumps the text revision. */
    val onSetText: ((String) -> Unit)? = null,
    val focusFlow: Flow<Unit>? = null,
) {
    /**
     * Convenience constructor preserving the original flat call shape; the
     * parameter order is unchanged so positional callers keep working, with
     * the new [id] appended last (only ever passed by name). [editable] is
     * derived from whether the field has an [onChange].
     */
    constructor(
        text: String,
        textRevision: Int = 0,
        hint: String? = null,
        error: String? = null,
        vl: Vl? = null,
        autocompleteOptions: ImmutableList<String> = persistentListOf(),
        focusFlow: Flow<Unit>? = null,
        onChange: ((String) -> Unit)? = null,
        onSetText: ((String) -> Unit)? = null,
        id: String = "",
    ) : this(
        state = TextFieldState(
            id = id,
            text = text,
            revision = textRevision,
            hint = hint,
            error = error,
            vl = vl,
            autocompleteOptions = autocompleteOptions,
            editable = onChange != null,
        ),
        onChange = onChange,
        onSetText = onSetText,
        focusFlow = focusFlow,
    )

    val text get() = state.text
    val textRevision get() = state.revision
    val hint get() = state.hint
    val error get() = state.error
    val vl get() = state.vl
    val autocompleteOptions get() = state.autocompleteOptions

    companion object {
        val empty = TextFieldModel(id = "", text = "")

        /**
         * Builds a field model from an atomic [cell] snapshot. The text and
         * the revision must always come from the same [TextCell] emission —
         * never sample them separately, or a lagging validation emission
         * could pair stale text with a fresh revision and force the UI edit
         * buffer to adopt outdated text.
         *
         * The field's [TextFieldState.id] is taken from the [handle].
         */
        fun of(
            cell: TextCell,
            handle: TextFieldHandle,
            validated: Validated<String>,
            hint: String? = null,
            // Pass null to render the field read-only.
            onChange: ((String) -> Unit)? = handle::onChange,
        ) = TextFieldModel(
            id = handle.id,
            text = cell.text,
            textRevision = cell.revision,
            hint = hint,
            error = (validated as? Validated.Failure)?.error
                ?.takeUnless { cell.text.isEmpty() },
            onChange = onChange,
            onSetText = handle::setText,
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
