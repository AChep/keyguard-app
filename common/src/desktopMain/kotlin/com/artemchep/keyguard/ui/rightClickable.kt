package com.artemchep.keyguard.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

actual fun Modifier.rightClickable(
    onClick: (() -> Unit)?,
) = this
    .composed {
        val enabled = onClick != null
        val updatedOnClick = rememberUpdatedState(onClick)

        val gesture = if (enabled) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitEventFirstDown()
                    if (event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        updatedOnClick.value?.invoke()
                    }
                }
            }
        } else {
            Modifier
        }
        Modifier
            .then(gesture)
    }

private suspend fun AwaitPointerEventScope.awaitEventFirstDown(): PointerEvent {
    var event: PointerEvent
    do {
        event = awaitPointerEvent()
    } while (
        !event.changes.all { it.changedToDown() }
    )
    return event
}
