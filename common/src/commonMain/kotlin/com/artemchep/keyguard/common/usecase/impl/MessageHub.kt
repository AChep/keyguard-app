package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.platform.WindowId
import org.kodein.di.DirectDI
import kotlin.uuid.Uuid

class MessageHubImpl() : MessageHub, ShowMessage {
    constructor(directDI: DirectDI) : this()

    private val state = mutableListOf<Entry>()

    private class Entry(
        val id: String,
        val key: String,
        val windowId: WindowId,
        val onMessage: (ToastMessage) -> Unit,
    )

    override fun register(
        key: String,
        windowId: WindowId,
        onMessage: (ToastMessage) -> Unit,
    ): () -> Unit {
        val id = Uuid.random().toString()
        val entry = Entry(
            id = id,
            key = key,
            windowId = windowId,
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
        val matchingWindowConsumers = kotlin.run {
            val targetWindowId = value.windowId
                ?: return@run state
            state
                .filter { it.windowId == targetWindowId }
        }

        val handler = kotlin.run {
            if (target != null) {
                return@run matchingWindowConsumers
                    .maxByOrNull {
                        it.key.commonPrefixWith(target).length
                    }
            }
            null
        } ?: matchingWindowConsumers.firstOrNull()
        handler?.onMessage?.invoke(value)
    }
}
