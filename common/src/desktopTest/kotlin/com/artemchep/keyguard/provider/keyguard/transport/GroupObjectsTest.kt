package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxCodec
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxOpening
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GroupObjectsTest {
    private val cryptoGenerator = NativeCryptoGenerator()
    private val crypto = NativeP2pCrypto(cryptoGenerator)
    private val groupKey = ByteArray(32) { (it * 7).toByte() }
    private val otherKey = ByteArray(32) { (it * 11).toByte() }
    private val objectKey = ObjectKey(ByteArray(32) { (it * 13).toByte() }, covers = emptyList())
    private val otherObjectKey = ObjectKey(ByteArray(32) { (it * 17).toByte() }, covers = listOf("removed"))

    private val words = "otter\nlynx\nheron".encodeToByteArray()
    private val hash = BlobExchange.hashOf(crypto, words)
    private val otherHash = BlobExchange.hashOf(crypto, "badger".encodeToByteArray())

    private fun objects(
        key: ByteArray = groupKey,
        label: String = SyncScope.APP.roomLabel,
        objects: ObjectKey = objectKey,
    ) = GroupObjects.derive(objects, key, label, cryptoGenerator)

    @Test
    fun `two devices of one group derive the same ids and keys`() {
        // Each device derives its own, from the object key and the group key.
        val ofA = objects()
        val ofB = objects(groupKey.copyOf(), objects = ObjectKey(objectKey.key.copyOf(), objectKey.covers))
        assertEquals(ofA.id(hash), ofB.id(hash))
        assertEquals(ofA.tag(hash), ofB.tag(hash))
        assertEquals(MailboxLimits.ITEM_ID_LENGTH, ofA.id(hash).length)
        assertTrue(ofA.id(hash).all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(isTag(ofA.tag(hash)))
        // What one device seals, the other one opens.
        val sealed = ofA.seal(hash, MailboxCodec.encodeBlob(words))
        val plaintext = assertIs<SealOpening.Opened>(ofB.open(hash, sealed, maxSize = 1024)).plaintext
        val opening = MailboxCodec.decodeBlob(plaintext, hash, crypto::sha256, maxBlobSize = 1024)
        assertContentEquals(words, assertIs<MailboxOpening.Valid<ByteArray>>(opening).item)
        // Each seal draws its own IV.
        assertNotEquals(sealed.toList(), ofA.seal(hash, MailboxCodec.encodeBlob(words)).toList())
    }

    @Test
    fun `the derivation is the one documented`() {
        val label = SyncScope.APP.roomLabel
        val idKey = cryptoGenerator.hkdf(
            seed = objectKey.key,
            salt = groupKey,
            info = (GroupObjects.ID_INFO + label).encodeToByteArray(),
            length = 32,
        )
        val id = P2pEncoding.hex(cryptoGenerator.hmacSha256(idKey, hash.encodeToByteArray()))
        val tag = P2pEncoding.hex(cryptoGenerator.hmacSha256(idKey, "tag\n$hash".encodeToByteArray()).copyOf(16))
        assertEquals(id, objects().id(hash))
        assertEquals(tag, objects().tag(hash))
        assertEquals(objectKey.keyId(crypto::sha256), objects().keyId)
        // The sealing key of the blob opens what the keys seal.
        val sealingKey = cryptoGenerator.hkdf(
            seed = objectKey.key,
            salt = groupKey,
            info = (GroupObjects.KEY_INFO + label + hash).encodeToByteArray(),
            length = MailboxSeal.KEY_SIZE,
        )
        val sealed = objects().seal(hash, MailboxCodec.encodeBlob(words))
        assertIs<SealOpening.Opened>(MailboxSeal.open(sealed, sealingKey, maxSize = 1024))
    }

    @Test
    fun `ids differ across object keys, groups, scopes and blobs`() {
        val ids = listOf(
            objects().id(hash),
            objects(objects = otherObjectKey).id(hash),
            objects(key = otherKey).id(hash),
            objects(label = "another-scope").id(hash),
            objects().id(otherHash),
        )
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(objects().tag(hash), objects().tag(otherHash))
        assertNotEquals(objects().tag(hash), objects(key = otherKey).tag(hash))
        assertNotEquals(objects().tag(hash), objects(objects = otherObjectKey).tag(hash))
        assertNotEquals(objects().keyId, objects(objects = otherObjectKey).keyId)
        // The tag is not the id.
        assertNotEquals(objects().id(hash).take(MailboxLimits.TAG_LENGTH), objects().tag(hash))
    }

    @Test
    fun `an object opens with the keys of its group and blob only`() {
        val sealed = objects().seal(hash, MailboxCodec.encodeBlob(words))
        // Another object key, group, scope, or blob: the MAC does not match.
        assertEquals(SealOpening.Corrupt, objects(objects = otherObjectKey).open(hash, sealed, maxSize = 1024))
        assertEquals(SealOpening.Corrupt, objects(key = otherKey).open(hash, sealed, maxSize = 1024))
        assertEquals(SealOpening.Corrupt, objects(label = "another-scope").open(hash, sealed, maxSize = 1024))
        assertEquals(SealOpening.Corrupt, objects().open(otherHash, sealed, maxSize = 1024))
        // A changed object does not open; a changed cipher byte names a cipher this build lacks.
        for (index in sealed.indices) {
            val flipped = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val expected = if (index == 0) SealOpening.Unsupported else SealOpening.Corrupt
            assertEquals(expected, objects().open(hash, flipped, maxSize = 1024), "byte $index")
        }
        // One that opens to more than it may is refused as such.
        assertEquals(SealOpening.TooLarge, objects().open(hash, sealed, maxSize = 10))
    }

    @Test
    fun `a group key of another size is refused`() {
        assertFailsWith<IllegalArgumentException> { objects(key = ByteArray(16)) }
    }
}
