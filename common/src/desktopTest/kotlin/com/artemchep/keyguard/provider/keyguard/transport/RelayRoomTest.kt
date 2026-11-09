package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RelayRoomTest {
    private val crypto = NativeCryptoGenerator()
    private val groupKey = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun `an invite room derives from its secret`() {
        val secret = ByteArray(32) { it.toByte() }
        val invite = RelayRoom.forInvite(secret, crypto)
        assertTrue(RelayCodec.isRoomId(invite.id))
        assertEquals(invite.id, RelayRoom.forInvite(secret.copyOf(), crypto).id)
        val expected = crypto.hmacSha256(secret, RelayRoom.INVITE_ROOM_LABEL.encodeToByteArray()).toHexString()
        assertEquals(expected, invite.id)
        assertEquals(64, invite.signalingKey.data.size)
    }

    @Test
    fun `a pair room derives from the shared secret, the group key and both keys`() {
        val shared = ByteArray(32) { 7 }
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val room = RelayRoom.forPair(shared, groupKey, a, b, null, SyncScope.APP, crypto)
        assertTrue(RelayCodec.isRoomId(room.id))
        val info = (RelayRoom.PAIR_ROOM_INFO + SyncScope.APP.roomLabel).encodeToByteArray() + a + b
        val secret = crypto.hkdf(seed = shared, salt = groupKey, info = info, length = 32)
        val expected = crypto.hmacSha256(secret, SyncScope.APP.roomLabel.encodeToByteArray()).toHexString()
        assertEquals(expected, room.id)
        // Both devices name the same room, whichever key is theirs.
        assertEquals(room.id, RelayRoom.forPair(shared, groupKey, b, a, null, SyncScope.APP, crypto).id)
        // Another secret, group or device is another room.
        assertNotEquals(room.id, RelayRoom.forPair(ByteArray(32) { 8 }, groupKey, a, b, null, SyncScope.APP, crypto).id)
        assertNotEquals(room.id, RelayRoom.forPair(shared, ByteArray(32), a, b, null, SyncScope.APP, crypto).id)
        val other = ByteArray(32) { 3 }
        assertNotEquals(room.id, RelayRoom.forPair(shared, groupKey, a, other, null, SyncScope.APP, crypto).id)
        assertNotEquals(room.id, RelayRoom.forInvite(shared, crypto).id)
    }

    @Test
    fun `two devices find the room of their pair, and a third device another one`() {
        val a = NativeCryptoNoise.generateKeyPair()
        val b = NativeCryptoNoise.generateKeyPair()
        val c = NativeCryptoNoise.generateKeyPair()

        fun room(self: NativeNoiseKeyPair, peer: ByteArray): String {
            val shared = NativeCryptoPrimitives.x25519(self.privateKey, peer)
            return RelayRoom.forPair(shared, groupKey, self.publicKey, peer, null, SyncScope.APP, crypto).id
        }
        assertEquals(room(a, b.publicKey), room(b, a.publicKey))
        // The third device knows both public keys and the group key, but not the pair's secret.
        assertNotEquals(room(a, b.publicKey), room(c, b.publicKey))
        assertNotEquals(room(a, b.publicKey), room(c, a.publicKey))
    }
}
