package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeMlKemKeyPair
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairMailboxTest {
    private val crypto = NativeCryptoGenerator()
    private val groupKey = ByteArray(32) { (it * 5).toByte() }

    private class Device(
        val noise: NativeNoiseKeyPair = NativeCryptoNoise.generateKeyPair(),
        val kem: NativeMlKemKeyPair = NativeCryptoPrimitives.mlKemGenerate(),
    )

    private val a = Device()
    private val b = Device()
    private val c = Device()

    /** The keys of the pair of [self] and [peer], as [self] derives them. */
    private fun mailbox(
        self: Device,
        peer: Device,
        peerKemPub: ByteArray = peer.kem.publicKey,
    ): PairMailbox {
        val shared = NativeCryptoPrimitives.x25519(self.noise.privateKey, peer.noise.publicKey)
        val kem = assertNotNull(PairKemKeys.ofOrNull(self.kem.privateKey, peerKemPub))
        val room = RelayRoom.forPair(
            sharedSecret = shared,
            groupKey = groupKey,
            localNoisePub = self.noise.publicKey,
            peerNoisePub = peer.noise.publicKey,
            kem = kem,
            scope = SyncScope.APP,
            cryptoGenerator = crypto,
        )
        return assertNotNull(room.mailbox)
    }

    @Test
    fun `the two devices of a pair derive the keys of each direction alike`() {
        val ofA = mailbox(a, b)
        val ofB = mailbox(b, a)
        // What one device leaves is what the other one asks for.
        assertEquals(ofA.outbox.stateId, ofB.inbox.stateId)
        assertEquals(ofA.inbox.stateId, ofB.outbox.stateId)
        assertNotEquals(ofA.outbox.stateId, ofA.inbox.stateId)
        assertEquals(MailboxLimits.ITEM_ID_LENGTH, ofA.outbox.stateId.length)
        assertTrue(ofA.outbox.stateId.all { it in '0'..'9' || it in 'a'..'f' })
        // Another pair has unrelated ids, so the relay can not link them.
        val other = mailbox(a, c)
        assertNotEquals(ofA.outbox.stateId, other.outbox.stateId)
        // Tags are keyed per direction, and cover the content.
        val content = "content".encodeToByteArray()
        assertEquals(ofA.outbox.tag(content), ofB.inbox.tag(content))
        assertNotEquals(ofA.outbox.tag(content), ofA.inbox.tag(content))
        assertNotEquals(ofA.outbox.tag(content), ofA.outbox.tag("other".encodeToByteArray()))
        assertTrue(isTag(ofA.outbox.tag(content)))
    }

    @Test
    fun `the room of an enrollment has no items`() {
        assertNull(RelayRoom.forInvite(ByteArray(32) { 1 }, crypto).mailbox)
    }

    @Test
    fun `an item opens with the key of its direction only`() {
        val ofA = mailbox(a, b)
        val ofB = mailbox(b, a)
        val plaintext = Random(3).nextBytes(10_000)
        val sealed = ofA.outbox.seal(plaintext)
        // A format byte and an ML-KEM-1024 ciphertext come first; the sealed rest shows a
        // size range, not the size.
        assertEquals(1, sealed[0].toInt())
        assertTrue(sealed.size >= 1 + 1568 + 49)
        assertContentEquals(plaintext, ofB.inbox.open(sealed, maxSize = 10_000))
        // Only the recipient holds the ML-KEM private key: not even the sender opens it.
        assertNull(ofA.outbox.open(sealed, maxSize = 10_000))
        // The other direction, and another pair, have other keys.
        assertNull(ofB.outbox.open(sealed, maxSize = 10_000))
        assertNull(ofA.inbox.open(sealed, maxSize = 10_000))
        assertNull(mailbox(c, a).inbox.open(sealed, maxSize = 10_000))
        // Each seal encapsulates a fresh secret and draws its own IV.
        assertNotEquals(sealed.toList(), ofA.outbox.seal(plaintext).toList())
    }

    @Test
    fun `an item needs both the X25519 keys of the pair and the ML-KEM key of the recipient`() {
        val plaintext = "the state".encodeToByteArray()
        // Sealed to the ML-KEM key of another device, with the X25519 keys of the pair.
        val toWrongKem = mailbox(a, b, peerKemPub = c.kem.publicKey).outbox.seal(plaintext)
        assertNull(mailbox(b, a).inbox.open(toWrongKem, maxSize = 1_000))
        // The device that holds that ML-KEM key lacks the X25519 keys of the pair.
        assertNull(mailbox(c, a).inbox.open(toWrongKem, maxSize = 1_000))
    }

    @Test
    fun `a pair whose peer published a broken ML-KEM key gets no mailbox`() {
        // The coefficients of this key are not reduced modulo q: it fails the FIPS 203 check.
        assertNull(PairKemKeys.ofOrNull(a.kem.privateKey, ByteArray(1568) { -1 }))
        assertNull(PairKemKeys.ofOrNull(a.kem.privateKey, b.kem.publicKey.copyOf(1567)))
        assertNull(PairKemKeys.ofOrNull(a.kem.privateKey.copyOf(3167), b.kem.publicKey))
        val shared = NativeCryptoPrimitives.x25519(a.noise.privateKey, b.noise.publicKey)
        val (aPub, bPub) = a.noise.publicKey to b.noise.publicKey
        assertNull(RelayRoom.forPair(shared, groupKey, aPub, bPub, null, SyncScope.APP, crypto).mailbox)
    }

    @Test
    fun `a changed item does not open`() {
        val ofA = mailbox(a, b)
        val ofB = mailbox(b, a)
        val sealed = ofA.outbox.seal("the state".encodeToByteArray())
        for (index in sealed.indices) {
            val flipped = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(ofB.inbox.open(flipped, maxSize = 1_000), "byte $index")
        }
        assertNull(ofB.inbox.open(sealed.copyOf(sealed.size - 1), maxSize = 1_000))
        assertNull(ofB.inbox.open(ByteArray(0), maxSize = 1_000))
    }

    @Test
    fun `an item that opens to more than it may is refused`() {
        val ofA = mailbox(a, b)
        val ofB = mailbox(b, a)
        // Zeros compress well: a small item, a large plaintext.
        val sealed = ofA.outbox.seal(ByteArray(1_000_000))
        assertTrue(sealed.size < 10_000)
        assertNull(ofB.inbox.open(sealed, maxSize = 999_999))
        assertEquals(1_000_000, ofB.inbox.open(sealed, maxSize = 1_000_000)?.size)
    }
}
