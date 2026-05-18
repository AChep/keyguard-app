package com.artemchep.keyguard.util.signalr.internal.transports

import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSocketTransportTest {
    @Test
    fun `text frames are preserved for SignalR handshake responses`() {
        val payload = Frame.Text("{}\u001e")
            .toSignalRPayloadOrNull()

        assertEquals("{}\u001e", payload?.decodeToString())
    }

    @Test
    fun `binary frames are preserved for MessagePack hub messages`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)

        val payload = Frame.Binary(
            fin = true,
            data = bytes,
        ).toSignalRPayloadOrNull()

        assertContentEquals(bytes, payload)
    }

    @Test
    fun `ping frames preserve vaultwarden protocol ping payloads`() {
        val bytes = byteArrayOf(0x01, 0x91.toByte(), 0x06)

        val payload = Frame.Ping(bytes)
            .toSignalRPayloadOrNull()

        assertContentEquals(bytes, payload)
    }

    @Test
    fun `pong and close frames are ignored by SignalR payload mapping`() {
        assertNull(Frame.Pong(byteArrayOf(0x01)).toSignalRPayloadOrNull())
        assertNull(Frame.Close().toSignalRPayloadOrNull())
    }

    @Test
    fun `handshake request can be sent as a text frame without binary conversion`() {
        val frame = Frame.Text("""{"protocol":"messagepack","version":1}""" + "\u001e")

        assertEquals(
            """{"protocol":"messagepack","version":1}""" + "\u001e",
            frame.readText(),
        )
    }
}
