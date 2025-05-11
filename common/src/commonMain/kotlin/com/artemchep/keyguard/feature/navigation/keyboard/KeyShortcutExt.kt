package com.artemchep.keyguard.feature.navigation.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun RememberStateFlowScope.interceptKeyEvents(
    vararg shortcuts: Pair<KeyShortcut, Flow<(() -> Unit)?>>,
): () -> Unit {
    val interceptorFlow = shortcuts
        .map { (shortcut, callbackFlow) ->
            callbackFlow
                .map { callback ->
                    shortcut to callback
                }
        }
        .combineToList()
        .map { shortcutsToCallbacks ->
            val shortcutsToCallbacksMap = shortcutsToCallbacks
                .asSequence()
                .mapNotNull { (shortcut, callback) ->
                    callback
                        ?: return@mapNotNull null
                    shortcut to callback
                }
                .toMap()
            if (shortcutsToCallbacksMap.isEmpty()) {
                return@map null
            }

            // lambda
            interceptor@{ keyEvent: KeyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@interceptor false
                }

                val key = when (CurrentPlatform) {
                    // On MacOS the Delete button is actually the
                    // Backspace button.
                    is Platform.Desktop.MacOS,
                    is Platform.Mobile,
                        -> when (val key = keyEvent.key) {
                        Key.Backspace -> Key.Delete
                        else -> key
                    }

                    else -> keyEvent.key
                }
                val isCtrlPressed = when (CurrentPlatform) {
                    // On MacOS all the shortcuts that use the Ctrl key
                    // make more sense when they use the Command key
                    // instead.
                    is Platform.Desktop.MacOS -> keyEvent.isMetaPressed
                    else -> keyEvent.isCtrlPressed
                }

                val shortcut = KeyShortcut(
                    key = key,
                    isCtrlPressed = isCtrlPressed,
                    isShiftPressed = keyEvent.isShiftPressed,
                    isAltPressed = keyEvent.isAltPressed,
                )
                val callback = shortcutsToCallbacksMap[shortcut]
                    ?: return@interceptor false
                callback()
                true
            }
        }
    return interceptKeyEvent(interceptorFlow)
}
