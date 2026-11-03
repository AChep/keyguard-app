package com.artemchep.keyguard.feature.home.settings.shortcuts

import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutRecording

data class ShortcutRecorderState(
    val action: GlobalShortcutAction,
    val busy: Boolean,
    val error: GlobalShortcutError?,
    val startRecording: suspend () -> GlobalShortcutRecording,
    val onSave: (GlobalShortcutBinding) -> Unit,
    val onClearError: () -> Unit,
    val onDismiss: () -> Unit,
)
