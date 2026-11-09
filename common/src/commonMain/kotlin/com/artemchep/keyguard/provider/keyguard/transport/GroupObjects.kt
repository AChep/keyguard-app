package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey

/**
 * The keys of the objects the devices of a sync group share on the relay: one object per
 * blob, such as the words of a wordlist, see
 * [com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind.BLOB]. Any device that holds
 * the blob writes its object once, and any device that needs it fetches it. The keys derive
 * from the object key of the group, see [ObjectKey], the 32-byte group key, the [label] of
 * the scope of the group, see [com.artemchep.keyguard.feature.p2psync.model.SyncScope.roomLabel],
 * and the blob `hash`, see [com.artemchep.keyguard.feature.p2psync.session.BlobExchange.hashOf]:
 *
 * - `idKey = HKDF-SHA256(ikm = objectKey, salt = groupKey, info = ID_INFO || label)`, 32
 *   bytes, names the objects: [id] is `HMAC-SHA256(idKey, hash)` and [tag] is the first 16
 *   bytes of `HMAC-SHA256(idKey, "tag\n" + hash)`, both in hex.
 * - `HKDF-SHA256(ikm = objectKey, salt = groupKey, info = KEY_INFO || label || hash)`, 64
 *   bytes, seals the object of the blob, see [MailboxSeal]: a key of that blob alone, so an
 *   object the relay swaps for another one does not open.
 *
 * The relay sees neither the hashes nor the content. A device the group removed keeps the
 * group key, but never gets the object key the group writes once it learns of the removal:
 * it can not name, open or delete the objects under that key.
 */
class GroupObjects private constructor(
    private val objectKey: ByteArray,
    private val groupKey: ByteArray,
    private val label: String,
    private val idKey: ByteArray,
    /** The id of the object key, see [ObjectKey.keyId]. */
    val keyId: String,
    private val cryptoGenerator: CryptoGenerator,
) {
    companion object {
        /** The HKDF-SHA256 info prefix of the key that names the objects. */
        const val ID_INFO: String = "keyguard/p2p/object-id/v1"

        /** The HKDF-SHA256 info prefix of the key that seals an object. */
        const val KEY_INFO: String = "keyguard/p2p/object-key/v1"

        private const val GROUP_KEY_SIZE = 32
        private const val ID_KEY_SIZE = 32
        private const val TAG_LABEL = "tag\n"
        private const val TAG_SIZE = MailboxLimits.TAG_LENGTH / 2

        /**
         * Derives the keys of the objects of the group with the [objectKey] and the
         * [groupKey], in the scope with the [label].
         */
        fun derive(
            objectKey: ObjectKey,
            groupKey: ByteArray,
            label: String,
            cryptoGenerator: CryptoGenerator,
        ): GroupObjects {
            require(groupKey.size == GROUP_KEY_SIZE) { "The group key is not $GROUP_KEY_SIZE bytes" }
            val idKey = cryptoGenerator.hkdf(
                seed = objectKey.key,
                salt = groupKey,
                info = (ID_INFO + label).encodeToByteArray(),
                length = ID_KEY_SIZE,
            )
            return GroupObjects(
                objectKey = objectKey.key.copyOf(),
                groupKey = groupKey.copyOf(),
                label = label,
                idKey = idKey,
                keyId = objectKey.keyId(cryptoGenerator::hashSha256),
                cryptoGenerator = cryptoGenerator,
            )
        }
    }

    /** The id of the object of the blob [hash]: `HMAC-SHA256(idKey, hash)`, in hex. */
    fun id(hash: String): String = P2pEncoding.hex(cryptoGenerator.hmacSha256(idKey, hash.encodeToByteArray()))

    /**
     * The tag of the object of the blob [hash], which the relay requires: the first 16
     * bytes of `HMAC-SHA256(idKey, "tag\n" + hash)`, in hex. Every device writes the same.
     */
    fun tag(hash: String): String {
        val mac = cryptoGenerator.hmacSha256(idKey, (TAG_LABEL + hash).encodeToByteArray())
        return P2pEncoding.hex(mac.copyOf(TAG_SIZE))
    }

    /** Seals the [plaintext] of the object of the blob [hash]. */
    fun seal(hash: String, plaintext: ByteArray): ByteArray = withKey(hash) { key ->
        MailboxSeal.seal(plaintext, key, cryptoGenerator)
    }

    /**
     * Opens the [sealed] object of the blob [hash], to at most [maxSize] bytes, see
     * [MailboxSeal.open]: an object of another blob does not open, as if it was changed.
     */
    fun open(hash: String, sealed: ByteArray, maxSize: Long): SealOpening = withKey(hash) { key ->
        MailboxSeal.open(sealed, key, maxSize)
    }

    private inline fun <T> withKey(hash: String, block: (ByteArray) -> T): T = cryptoGenerator
        .hkdf(
            seed = objectKey,
            salt = groupKey,
            info = (KEY_INFO + label + hash).encodeToByteArray(),
            length = MailboxSeal.KEY_SIZE,
        )
        .useAndClear(block)

    override fun toString(): String = "GroupObjects"
}
