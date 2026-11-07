package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.recordLogDebug
import kotlin.uuid.Uuid

class MessageHubImpl : MessageHub, ShowMessage {
    private val state = mutableListOf<Entry>()

    private class Entry(
        val id: String,
        val key: String,
        val windowId: WindowId,
        val isFocused: () -> Boolean,
        val onMessage: (ToastMessage) -> Unit,
    )

    override fun register(
        key: String,
        windowId: WindowId,
        isFocused: () -> Boolean,
        onMessage: (ToastMessage) -> Unit,
    ): () -> Unit {
        val id = Uuid.random().toString()
        val entry = Entry(
            id = id,
            key = key,
            windowId = windowId,
            isFocused = isFocused,
            onMessage = onMessage,
        )

        state += entry
        return {
            state -= entry
        }
    }

    override fun copy(
        value: ToastMessage,
        target: String?,
    ) {
        // A message that names its window belongs there, even if
        // the window is not focused, e.g. an approval popup. Others go
        // to the focused window.
        val targetWindowId = value.windowId
        val consumers = if (targetWindowId != null) {
            state.filter { it.windowId == targetWindowId }
        } else {
            state.filter { it.isFocused() }
        }

        val handler = kotlin.run {
            if (target != null) {
                return@run consumers
                    .maxByOrNull {
                        it.key.commonPrefixWith(target).length
                    }
            }
            null
        } ?: consumers.firstOrNull()
        if (handler == null) {
            recordLogDebug {
                "[MessageHub] no consumer for a message: window=${targetWindowId?.value}, target=$target"
            }
            return
        }
        handler.onMessage(value)
    }
}
