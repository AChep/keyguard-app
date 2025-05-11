package com.artemchep.keyguard.feature.navigation.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key

@Immutable
data class KeyShortcut(
    val key: Key,
    val isCtrlPressed: Boolean = false,
    val isShiftPressed: Boolean = false,
    val isAltPressed: Boolean = false,
)
