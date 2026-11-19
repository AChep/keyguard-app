package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
internal actual fun DialogContentPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    properties: PopupProperties,
    content: @Composable () -> Unit,
) = Popup(
    popupPositionProvider = popupPositionProvider,
    onDismissRequest = onDismissRequest,
    properties = properties,
    content = content,
)
