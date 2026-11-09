package com.artemchep.keyguard.util.webrtc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Golden vectors shared with `keyguard-webrtc-core`; changing one is an ABI break. */
class NativeWebrtcWireTest {
    @Test
    fun packedFailuresDecodeToTheirCategories() {
        val vectors = mapOf(
            0x0103_0800L to WebRtcFailure.INVALID_ARGUMENT,
            0x0203_0C00L to WebRtcFailure.INTERNAL,
            0x0303_0C00L to WebRtcFailure.INTERNAL,
            0x0403_0800L to WebRtcFailure.INPUT_TOO_LONG,
            0x0503_0800L to WebRtcFailure.INVALID_HANDLE,
            0x0603_0900L to WebRtcFailure.CLOSED,
            0x0703_0700L to WebRtcFailure.BUSY,
            0x0803_0B00L to WebRtcFailure.TIMEOUT,
            0x0903_0B00L to WebRtcFailure.INVALID_STATE,
            0x0A03_0B00L to WebRtcFailure.STACK,
            0xFF03_0B00L to WebRtcFailure.PROTOCOL,
        )
        vectors.forEach { (low, failure) ->
            assertEquals(failure, decodeNativeWebrtcFailure(Long.MIN_VALUE or low), "$low")
        }
        assertEquals(Long.MIN_VALUE or 0x0103_0800L, NATIVE_WEBRTC_INVALID_ARGUMENT)
        // A foreign domain or a reserved bit is outside the ABI.
        assertEquals(WebRtcFailure.PROTOCOL, decodeNativeWebrtcFailure(Long.MIN_VALUE or 0x0601_0900L))
        assertEquals(
            WebRtcFailure.PROTOCOL,
            decodeNativeWebrtcFailure(Long.MIN_VALUE or (1L shl 56) or 0x0603_0900L),
        )
    }

    @Test
    fun eventsDecodeFromTheDocumentedLayout() {
        assertEquals(WebRtcEvent.IceGatheringComplete, decode(2))
        assertEquals(WebRtcEvent.ConnectionState(WebRtcConnectionState.CONNECTED), decode(3, 3))
        assertEquals(WebRtcEvent.ConnectionState(WebRtcConnectionState.UNKNOWN), decode(3, 99))
        assertEquals(WebRtcEvent.ChannelOpen, decode(4))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<WebRtcEvent.ChannelMessage>(decode(5, 1, 2, 3)).data)
        assertContentEquals(byteArrayOf(), assertIs<WebRtcEvent.ChannelMessage>(decode(5)).data)
        assertEquals(WebRtcEvent.ChannelClose, decode(6))
        assertEquals(WebRtcEvent.BufferedLow, decode(7))
        assertEquals(WebRtcEvent.Error(0x0102_0304), decode(9, 4, 3, 2, 1))
        assertNull(decode(42), "an unknown kind is skipped")

        // Candidates pass through as the core wrote them.
        val candidate = """{"candidate":"candidate:1 1 udp 1 127.0.0.1 9 typ host","sdpMid":"0","sdpMLineIndex":0}"""
        assertEquals(WebRtcEvent.IceCandidate(candidate), decode(1, *candidate.encodeToByteArray()))
        val pair = """{"localType":"host","remoteType":"relay","protocol":"udp"}"""
        assertEquals(
            WebRtcEvent.SelectedPair(WebRtcSelectedPair("host", "relay", "udp")),
            decode(8, *pair.encodeToByteArray()),
        )
    }

    @Test
    fun configurationsEncodeAsTheCoreExpects() {
        assertEquals(
            """{"iceServers":[],"iceTransportPolicy":"all"}""",
            encodeConfig(WebRtcConfig()),
        )
        assertEquals(
            """{"iceServers":[{"urls":["turn:a:3478"],"username":"u","credential":"c"},""" +
                """{"urls":["stun:b"]}],"iceTransportPolicy":"relay","bindAddresses":["127.0.0.1:0"]}""",
            encodeConfig(
                WebRtcConfig(
                    iceServers = listOf(
                        WebRtcConfig.IceServer(listOf("turn:a:3478"), "u", "c"),
                        WebRtcConfig.IceServer(listOf("stun:b")),
                    ),
                    iceTransportPolicy = WebRtcIceTransportPolicy.RELAY,
                    bindAddresses = listOf("127.0.0.1:0"),
                ),
            ),
        )
    }

    private fun decode(vararg bytes: Byte): WebRtcEvent? {
        val buffer = ByteArray(NATIVE_WEBRTC_MAX_EVENT_BYTES)
        bytes.copyInto(buffer)
        return decodeNativeWebrtcEvent(buffer, bytes.size)
    }
}
