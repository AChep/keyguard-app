package com.artemchep.keyguard.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.artemchep.keyguard.feature.navigation.BackHandler

/** Register once before the window content, so subsequently opened popups handle Back first. */
@Composable
internal fun DesktopNavigationBackHandler(handler: BackHandler) {
    val windowInfo = LocalWindowInfo.current
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        // Keep the root registration stable. handleBack checks the live stacks and
        // does nothing at the root, including between recompositions.
        onBackCompleted = {
            // Compose also dispatches modified Escape. Read modifiers at event time
            // to preserve the app's plain-Escape navigation policy.
            val modifiers = windowInfo.keyboardModifiers
            val hasModifier = modifiers.isCtrlPressed || modifiers.isMetaPressed ||
                modifiers.isAltPressed || modifiers.isShiftPressed
            if (!hasModifier) {
                handler.handleBack()
            }
        },
    )
}
