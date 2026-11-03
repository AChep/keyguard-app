package com.artemchep.keyguard.feature.environments.ui

import com.artemchep.keyguard.feature.environments.EnvironmentEditorState
import com.artemchep.keyguard.feature.environments.EnvironmentOwnership
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource

/** An action is `null` while it is not available; the vault is locked if all of them are. */
data class EnvironmentsState(
    val busy: Boolean = false,
    /** The last operation failed; the next one resets it. */
    val failed: Boolean = false,
    /** `null` without an open draft. */
    val editor: EnvironmentEditor? = null,
    /** Replaces the open draft with a new one; does nothing while a write runs. */
    val onCreate: (() -> Unit)? = null,
    /** Replaces the open draft with the environment; does nothing while a write runs. */
    val onOpen: ((String) -> Unit)? = null,
    /** Closes the open draft and cancels its loading; does nothing while a write runs. */
    val onClose: (() -> Unit)? = null,
    val onDisableMount: ((String) -> Unit)? = null,
    val onMcpEnabledChange: ((Boolean) -> Unit)? = null,
)

/**
 * The open draft. Its actions belong to this draft: they do nothing once it
 * is replaced, closed or the vault locks. An action is `null` while it is
 * not available; a captured action still checks it on every call.
 */
data class EnvironmentEditor(
    val draft: EnvironmentEditorState,
    val onNameChange: ((String) -> Unit)?,
    val onDescriptionChange: ((String) -> Unit)?,
    val onOwnershipChange: ((accountId: String, ownership: EnvironmentOwnership) -> Unit)?,
    val onImportTextChange: ((String) -> Unit)?,
    val onPreviewImport: (() -> Unit)?,
    val onApplyImport: (() -> Unit)?,
    val onAddValue: (() -> Unit)?,
    /**
     * Adds a picked reference. A reference belongs to the account of the
     * draft, so this does nothing once the account changes.
     */
    val onAddReference: ((reference: EnvironmentValueSource.Reference, name: String) -> Unit)?,
    val onRemoveVariable: ((Int) -> Unit)?,
    val onMoveVariable: ((from: Int, to: Int) -> Unit)?,
    val onVariableNameChange: ((Int, String) -> Unit)?,
    val onVariableValueChange: ((Int, String) -> Unit)?,
    /** Changes the source of a variable, except to a reference: see [onVariableReferenceChange]. */
    val onVariableSourceChange: ((Int, EnvironmentValueSource) -> Unit)?,
    /**
     * Sets a picked reference, and names an unnamed variable with the [name].
     * Does nothing once the account of the draft changes.
     */
    val onVariableReferenceChange: ((Int, reference: EnvironmentValueSource.Reference, name: String) -> Unit)?,
    val onRevealToggle: (Int) -> Unit,
    /** The value of a literal variable, `null` once the draft is gone. */
    val literalValue: (Int) -> String?,
    /** `null` if the draft can not be saved. */
    val onSave: (() -> Unit)?,
    val onTrash: (() -> Unit)?,
    val onMount: ((path: String, bookmark: String?) -> Unit)?,
) {
    override fun toString(): String = "EnvironmentEditor([redacted])"
}

sealed interface EnvironmentsEffect {
    /** The access to the environment was not confirmed, so it was not opened. */
    data object Denied : EnvironmentsEffect

    data class Saved(
        val id: String,
    ) : EnvironmentsEffect

    data object Trashed : EnvironmentsEffect
}
