package com.artemchep.keyguard.feature.environments.mount

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

@Immutable
data class EnvironmentMountState(
    /**
     * The environment picker, `null` if the
     * environment was given up front.
     */
    val environment: Environment?,
    val path: TextFieldModel,
    /** Fills the path from a folder picker, `null` if not supported. */
    val onChooseFolder: (() -> Unit)?,
    /**
     * The command that shares the chosen folder with
     * the Flatpak sandbox, `null` outside of Flatpak.
     */
    val flatpakCommand: String?,
    val error: String?,
    val busy: Boolean,
    val onCreate: (() -> Unit)?,
    val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
) {
    @Immutable
    data class Environment(
        val name: String,
        val dropdown: ImmutableList<ContextItem>,
    )
}
