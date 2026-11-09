package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.Signaling
import com.artemchep.keyguard.feature.p2psync.transport.SignalingMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** TEST ONLY: a signaling room in memory; every device is addressed by its id. */
class InMemorySignalingHub {
    private val devices = MutableStateFlow<Map<String, Endpoint>>(emptyMap())

    /** Joins the device [id]; [deliver] decides which messages it sends arrive. */
    fun join(
        id: String,
        deliver: (to: String, payload: ByteArray) -> Boolean = { _, _ -> true },
    ): Signaling {
        val endpoint = Endpoint(id, deliver)
        devices.value += id to endpoint
        return endpoint
    }

    private inner class Endpoint(
        private val id: String,
        private val deliver: (String, ByteArray) -> Boolean,
    ) : Signaling {
        val inbox = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = Int.MAX_VALUE)

        override val presence: Flow<Set<String>> = devices.map { it.keys - id }

        override val incoming: Flow<SignalingMessage> = inbox

        override suspend fun send(to: String, payload: ByteArray) {
            if (!deliver(to, payload)) return
            devices.value[to]?.inbox?.emit(SignalingMessage(id, payload))
        }
    }
}
