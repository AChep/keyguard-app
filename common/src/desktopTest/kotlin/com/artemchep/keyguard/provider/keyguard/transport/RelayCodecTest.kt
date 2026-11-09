package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayCodecTest {
    private val peerA = "0123456789abcdef0123456789abcdef"
    private val peerB = "fedcba9876543210fedcba9876543210"
    private val room = "ab".repeat(32)

    @Test
    fun `client frames match the relay protocol`() {
        // The relay names the peer in its welcome; a join names only the room.
        assertEquals("""{"t":"join","room":"$room"}""", RelayCodec.join(room))
        assertEquals(
            """{"t":"pub","room":"$room","to":"*","data":"AQID"}""",
            RelayCodec.pub(room, "*", byteArrayOf(1, 2, 3)),
        )
        assertEquals("""{"t":"leave","room":"$room"}""", RelayCodec.leave(room))
        assertEquals("""{"t":"ping"}""", RelayCodec.PING)

        val forward = RelayCodec.forward(room, peerB, byteArrayOf(9, 8))
        assertEquals(0x03, forward[0].toInt())
        assertContentEquals(room.hexToByteArray(), forward.copyOfRange(1, 33))
        assertContentEquals(peerB.hexToByteArray(), forward.copyOfRange(33, 49))
        assertContentEquals(byteArrayOf(9, 8), forward.copyOfRange(49, forward.size))
    }

    @Test
    fun `a full session frame fits one relay message`() {
        // The relay's MAX_FRAME_BYTES, the largest message this build sends.
        assertEquals(65_536, RelayCodec.MAX_MESSAGE_BYTES)
        // Sessions encrypt frames of at most this size, then the relay stream forwards them.
        assertTrue(FrameCodec.MAX_FRAME_SIZE + NoiseByteStream.TAG_SIZE <= RelayForwardTransport.MAX_MESSAGE_SIZE)
        // A relay that allows less than that is no use.
        assertTrue(RelayForwardTransport.MIN_RELAY_MESSAGE_BYTES <= RelayCodec.MAX_MESSAGE_BYTES)
    }

    @Test
    fun `the welcome is decoded, ignoring what this build does not know`() {
        val welcome = RelayCodec.decodeText(
            """{"t":"welcome","v":1,"peer":"$peerA","features":["room-forward","future"],"limits":""" +
                """{"maxMessageBytes":65536,"messagesPerSecond":50,"bytesPerSecond":2097152,""" +
                """"roomsPerConnection":16,"peersPerRoom":4,"queuedFrames":128,"idleSeconds":60,"future":1},""" +
                """"extra":true}""",
        )
        assertEquals(
            RelayServerFrame.Welcome(1, peerA, setOf("room-forward", "future"), RelayLimits()),
            welcome,
        )
        assertTrue(assertIs<RelayServerFrame.Welcome>(welcome).isCompatible)
        // Missing limits take the relay's defaults; a larger message size is capped.
        assertEquals(
            RelayServerFrame.Welcome(1, peerA, setOf("room-forward"), RelayLimits(queuedFrames = 64)),
            RelayCodec.decodeText(
                """{"t":"welcome","v":1,"peer":"$peerA","features":["room-forward"],""" +
                    """"limits":{"queuedFrames":64,"maxMessageBytes":1048576}}""",
            ),
        )
        // Another version, or no forwarding: decoded, and not compatible.
        val v2 = assertIs<RelayServerFrame.Welcome>(
            RelayCodec.decodeText("""{"t":"welcome","v":2,"peer":"$peerA","features":["room-forward"]}"""),
        )
        assertEquals(false, v2.isCompatible)
        val bare = assertIs<RelayServerFrame.Welcome>(
            RelayCodec.decodeText("""{"t":"welcome","v":1,"peer":"$peerA","features":[]}"""),
        )
        assertEquals(false, bare.isCompatible)
        // A welcome that breaks the protocol is none.
        listOf(
            """{"t":"welcome","peer":"$peerA"}""",
            """{"t":"welcome","v":1,"peer":"short"}""",
            """{"t":"welcome","v":1,"peer":"$peerA","features":[1]}""",
            """{"t":"welcome","v":1,"peer":"$peerA","limits":{"queuedFrames":0}}""",
            """{"t":"welcome","v":1,"peer":"$peerA","limits":{"idleSeconds":-1}}""",
            """{"t":"welcome","v":1,"peer":"$peerA","limits":{"idleSeconds":1.5}}""",
        ).forEach { text -> assertNull(RelayCodec.decodeText(text), text) }
    }

    @Test
    fun `a relay that keeps items names their limits in its welcome`() {
        fun welcome(features: String, limits: String = "{}") = assertIs<RelayServerFrame.Welcome>(
            RelayCodec.decodeText("""{"t":"welcome","v":1,"peer":"$peerA","features":$features,"limits":$limits}"""),
        )
        // Without the feature, the limits of the items are ignored.
        assertNull(welcome("""["room-forward"]""", """{"mailboxStateBytes":1024}""").limits.mailbox)
        // With it, the protocol limits fill in what the relay leaves out.
        assertEquals(RelayMailboxLimits(), welcome("""["room-forward","mailbox"]""").limits.mailbox)
        assertEquals(
            RelayMailboxLimits(stateBytes = 1024, blobBytes = 2048, ttlSeconds = 3600),
            welcome(
                """["room-forward","mailbox"]""",
                """{"mailboxStateBytes":1024,"mailboxBlobBytes":2048,"mailboxTtlSeconds":3600}""",
            ).limits.mailbox,
        )
        // A relay never gets more than the protocol allows.
        assertEquals(
            RelayMailboxLimits(),
            welcome(
                """["room-forward","mailbox"]""",
                """{"mailboxStateBytes":99999999,"mailboxBlobBytes":99999999,"mailboxTtlSeconds":99999999}""",
            ).limits.mailbox,
        )
        // A limit that is not positive leaves the relay without items, but the welcome stands.
        val zero = welcome("""["room-forward","mailbox"]""", """{"mailboxStateBytes":0,"queuedFrames":64}""")
        assertNull(zero.limits.mailbox)
        assertEquals(64, zero.limits.queuedFrames)
        assertTrue(zero.isCompatible)
    }

    @Test
    fun `server frames are decoded`() {
        assertEquals(
            RelayServerFrame.Joined(room, setOf(peerA, peerB)),
            RelayCodec.decodeText("""{"t":"joined","room":"$room","peers":["$peerA","$peerB"]}"""),
        )
        assertEquals(
            RelayServerFrame.Joined(room, emptySet()),
            RelayCodec.decodeText("""{"t":"joined","room":"$room","peers":[]}"""),
        )
        val ticket = "00112233445566778899aabbccddeeff"
        assertEquals(
            RelayServerFrame.Joined(room, setOf(peerA), ticket),
            RelayCodec.decodeText("""{"t":"joined","room":"$room","peers":["$peerA"],"ticket":"$ticket"}"""),
        )
        // A malformed ticket is dropped, the join is kept.
        for (bad in listOf(ticket.uppercase(), ticket.drop(2), "")) {
            assertEquals(
                RelayServerFrame.Joined(room, setOf(peerA)),
                RelayCodec.decodeText("""{"t":"joined","room":"$room","peers":["$peerA"],"ticket":"$bad"}"""),
            )
        }
        assertEquals(
            RelayServerFrame.Presence(room, peerA, joined = true),
            RelayCodec.decodeText("""{"t":"presence","room":"$room","peer":"$peerA","state":"join"}"""),
        )
        assertEquals(
            RelayServerFrame.Presence(room, peerA, joined = false),
            RelayCodec.decodeText("""{"t":"presence","room":"$room","peer":"$peerA","state":"leave","extra":1}"""),
        )
        val msg = assertIs<RelayServerFrame.Msg>(
            RelayCodec.decodeText("""{"t":"msg","room":"$room","from":"$peerA","data":"AQID"}"""),
        )
        assertEquals(room, msg.room)
        assertEquals(peerA, msg.message.from)
        assertContentEquals(byteArrayOf(1, 2, 3), msg.message.data)
        assertEquals(RelayServerFrame.Pong, RelayCodec.decodeText("""{"t":"pong"}"""))
        // The type may come after the other fields; unknown fields are ignored.
        assertEquals(
            RelayServerFrame.Joined(room, setOf(peerA)),
            RelayCodec.decodeText("""{"peers":["$peerA","$peerA"],"room":"$room","extra":{"a":[1]},"t":"joined"}"""),
        )
        assertEquals(
            RelayServerFrame.Err(RelayError.NotJoined),
            RelayCodec.decodeText("""{"t":"err","code":"not_joined"}"""),
        )
        // A refused join names its room.
        assertEquals(
            RelayServerFrame.Err(RelayError.RoomFull, room),
            RelayCodec.decodeText("""{"t":"err","code":"room_full","room":"$room"}"""),
        )
        assertEquals(
            RelayServerFrame.Err(RelayError.TooManyRooms, room),
            RelayCodec.decodeText("""{"t":"err","code":"too_many_rooms","room":"$room"}"""),
        )
        assertEquals(
            RelayServerFrame.Err(RelayError.Unknown("new_code")),
            RelayCodec.decodeText("""{"t":"err","code":"new_code","room":null}"""),
        )
    }

    @Test
    fun `every relay error code maps`() {
        mapOf(
            "unauthorized" to RelayError.Unauthorized,
            "rate_limited" to RelayError.RateLimited,
            "room_full" to RelayError.RoomFull,
            "frame_too_big" to RelayError.FrameTooBig,
            "bad_frame" to RelayError.BadFrame,
            "not_joined" to RelayError.NotJoined,
            "too_many_rooms" to RelayError.TooManyRooms,
            "unknown_type" to RelayError.UnknownType,
        ).forEach { (code, error) -> assertEquals(error, RelayCodec.errorOf(code)) }
        // The relay dropped it.
        assertEquals(RelayError.Unknown("peer_taken"), RelayCodec.errorOf("peer_taken"))
    }

    @Test
    fun `malformed server frames are rejected`() {
        listOf(
            "",
            "[]",
            "not json",
            """{"t":"unknown"}""",
            """{"t":1}""",
            """{"t":pong}""",
            """{"t":null}""",
            """{"peers":[]}""",
            """{"t":"joined"}""",
            """{"t":"joined","room":"$room"}""",
            """{"t":"joined","room":"$room","peers":["$peerA",1]}""",
            """{"t":"joined","room":"$room","peers":["${peerA.uppercase()}"]}""",
            """{"t":"joined","room":"$room","peers":["abc"]}""",
            """{"t":"joined","room":"$room","peers":[$peerA]}""",
            """{"t":"joined","room":"$room","peers":"$peerA"}""",
            // Every room frame names a well-formed room.
            """{"t":"joined","peers":["$peerA"]}""",
            """{"t":"joined","room":"${room.uppercase()}","peers":[]}""",
            """{"t":"joined","room":"$peerA","peers":[]}""",
            """{"t":"presence","peer":"$peerA","state":"join"}""",
            """{"t":"presence","room":"ab","peer":"$peerA","state":"join"}""",
            """{"t":"msg","from":"$peerA","data":"AQID"}""",
            """{"t":"msg","room":null,"from":"$peerA","data":"AQID"}""",
            """{"t":"err","code":"room_full","room":"ab"}""",
            """{"t":"err","code":"room_full","room":7}""",
            """{"t":"presence","room":"$room","peer":"$peerA","state":"maybe"}""",
            """{"t":"presence","room":"$room","peer":"$peerA","state":join}""",
            """{"t":"presence","room":"$room","peer":null,"state":"join"}""",
            """{"t":"presence","room":"$room","state":"join"}""",
            """{"t":"msg","room":"$room","from":"$peerA"}""",
            """{"t":"msg","room":"$room","from":"$peerA","data":"AQI"}""",
            """{"t":"msg","room":"$room","from":"$peerA","data":"!!!!"}""",
            """{"t":"msg","room":"$room","from":"short","data":"AQID"}""",
            """{"t":"err"}""",
            """{"t":"err","code":7}""",
            """{"t":"err","code":room_full}""",
            """{"t":"pong"} {}""",
            """{"t":"msg","room":"$room","from":"$peerA","data":"${"A".repeat(2 * 64 * 1024)}"}""",
        ).forEach { text -> assertNull(RelayCodec.decodeText(text), text) }
    }

    @Test
    fun `deeply nested server frames are rejected before parsing`() {
        val depth = 60_000
        listOf(
            "[".repeat(depth) + "]".repeat(depth),
            """{"t":"pong","x":""" + "[".repeat(depth) + "]".repeat(depth) + "}",
            """{"t":"pong","x":[[[[[]]]]]}""",
        ).forEach { text -> assertNull(RelayCodec.decodeText(text), text.take(32)) }
    }

    @Test
    fun `forwarded binary frames are decoded strictly`() {
        val frame = assertIs<RelayForwardFrame>(
            RelayCodec.decodeBinary(RelayCodec.forward(room, peerA, byteArrayOf(5))),
        )
        assertEquals(room, frame.room)
        assertEquals(peerA, frame.from)
        assertContentEquals(byteArrayOf(5), frame.payload)
        // The header alone is a frame with an empty payload; one byte less is not.
        val header = RelayCodec.forward(room, peerA, ByteArray(0))
        assertEquals(0, assertIs<RelayForwardFrame>(RelayCodec.decodeBinary(header)).payload.size)
        assertNull(RelayCodec.decodeBinary(header.copyOf(header.size - 1)))
        // The old frame without a room: `0x02 | peer id | payload`.
        assertNull(RelayCodec.decodeBinary(byteArrayOf(0x02) + peerA.hexToByteArray() + ByteArray(40)))
        assertNull(RelayCodec.decodeBinary(ByteArray(64 * 1024 + 1) { 3 }))
    }

    @Test
    fun `endpoint urls are normalized`() {
        val https = RelayEndpoint("https://relay.example.com/")
        assertEquals("wss://relay.example.com/v1/ws", https.webSocketUrl)
        assertEquals("https://relay.example.com/v1/ice-servers", https.iceServersUrl)
        assertTrue(https.iceServersAtRelay)
        val same = RelayEndpoint("wss://relay.example.com", iceUrl = "https://relay.example.com/v1/ice-servers")
        assertTrue(same.iceServersAtRelay)
        val ws = RelayEndpoint(
            relayUrl = "ws://127.0.0.1:7400/v1/ws",
            iceUrl = "http://ice.example/x",
            token = "abcDEF0123456789-._~",
        )
        assertEquals("ws://127.0.0.1:7400/v1/ws", ws.webSocketUrl)
        assertEquals("http://ice.example/x", ws.iceServersUrl)
        assertEquals(false, ws.iceServersAtRelay)
        assertFailsWith<IllegalArgumentException> { RelayEndpoint("ftp://relay.example.com") }
        assertFailsWith<IllegalArgumentException> { RelayEndpoint("relay.example.com") }
        assertFailsWith<IllegalArgumentException> { RelayEndpoint("https://relay.example.com", token = "bad\r\ntoken") }
    }

}
