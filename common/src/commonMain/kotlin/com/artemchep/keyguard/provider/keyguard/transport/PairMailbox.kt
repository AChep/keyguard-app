package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

/**
 * The keys of the state items the two devices of a pair leave on the relay for each other,
 * see [com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind.STATE]: one set of keys
 * per direction. They derive from the secret of the room of the pair, see
 * [RelayRoom.forPair], which only the two devices compute, and from the ML-KEM-1024 key of
 * the recipient, see [PairKemKeys]: the relay can neither read nor forge an item, nor find
 * which items belong together. The blobs are the group's, see [GroupObjects].
 *
 * For each direction `from → to`, `HKDF-SHA256(secret, info = INFO || fromNoisePub ||
 * toNoisePub)` gives 96 bytes: a 32-byte key that names the items and a 64-byte key that
 * seals them, see [MailboxKeys]. The two directions get other keys, so an item never opens
 * as one of the other direction.
 */
class PairMailbox private constructor(
    /** The keys of the items this device leaves for its peer. */
    val outbox: MailboxKeys,
    /** The keys of the items the peer leaves for this device. */
    val inbox: MailboxKeys,
) {
    companion object {
        /** The HKDF-SHA256 info prefix of the keys of a direction. */
        const val INFO: String = "keyguard/p2p/mailbox/v1"

        private const val ID_KEY_SIZE = 32
        private const val SEAL_KEY_SIZE = MailboxSeal.KEY_SIZE

        /**
         * Derives the keys of both directions from the [secret] of the room of the pair of
         * this device, with the Noise key [localNoisePub], and its peer, [peerNoisePub], and
         * from the ML-KEM-1024 keys of the pair, [kem].
         */
        internal fun derive(
            secret: ByteArray,
            localNoisePub: ByteArray,
            peerNoisePub: ByteArray,
            kem: PairKemKeys,
            cryptoGenerator: CryptoGenerator,
        ): PairMailbox = PairMailbox(
            outbox = keys(secret, from = localNoisePub, to = peerNoisePub, cryptoGenerator).let { (id, seal) ->
                MailboxKeys(id, seal, MailboxKeys.Kem.Seal(kem.peerPublicKey), cryptoGenerator)
            },
            inbox = keys(secret, from = peerNoisePub, to = localNoisePub, cryptoGenerator).let { (id, seal) ->
                MailboxKeys(id, seal, MailboxKeys.Kem.Open(kem.localPrivateKey), cryptoGenerator)
            },
        )

        private fun keys(
            secret: ByteArray,
            from: ByteArray,
            to: ByteArray,
            cryptoGenerator: CryptoGenerator,
        ): Pair<ByteArray, ByteArray> = cryptoGenerator
            .hkdf(
                seed = secret,
                info = INFO.encodeToByteArray() + from + to,
                length = ID_KEY_SIZE + SEAL_KEY_SIZE,
            )
            .useAndClear { keys ->
                keys.copyOfRange(0, ID_KEY_SIZE) to keys.copyOfRange(ID_KEY_SIZE, ID_KEY_SIZE + SEAL_KEY_SIZE)
            }
    }

    override fun toString(): String = "PairMailbox"
}

/**
 * The ML-KEM-1024 keys of a pair: the [localPrivateKey] of this device opens the items its
 * peer leaves for it, and the [peerPublicKey] seals the items this device leaves for the
 * peer, see [MailboxKeys]. The public key is the one of the roster record of the peer.
 */
class PairKemKeys private constructor(
    val localPrivateKey: ByteArray,
    val peerPublicKey: ByteArray,
) {
    override fun toString(): String = "PairKemKeys"

    companion object {
        /** The size of an ML-KEM-1024 private key. */
        const val PRIVATE_KEY_SIZE: Int = 3168

        /** The size of an ML-KEM-1024 public key. */
        const val PUBLIC_KEY_SIZE: Int = 1568

        /**
         * Returns the keys of a pair, or `null` if either is malformed: a [peerPublicKey]
         * that fails the FIPS 203 check seals nothing, so a peer that published one gets
         * no relay storage, see [RelayRoom.mailbox].
         */
        fun ofOrNull(localPrivateKey: ByteArray, peerPublicKey: ByteArray): PairKemKeys? {
            if (localPrivateKey.size != PRIVATE_KEY_SIZE || peerPublicKey.size != PUBLIC_KEY_SIZE) return null
            val usable = try {
                NativeCryptoPrimitives.mlKemEncapsulate(peerPublicKey).sharedSecret.fill(0)
                true
            } catch (expected: NativeCryptoException) {
                false
            }
            return PairKemKeys(localPrivateKey, peerPublicKey).takeIf { usable }
        }
    }
}

/**
 * The keys of one direction of a pair, see [PairMailbox].
 *
 * The [idKey] names the state item, as the relay sees it: [stateId] is a capability,
 * unguessable without it. It also keys the tags of the items, see [tag], so equal content
 * has unequal tags in other pairs.
 *
 * An item is `u8 format | ML-KEM ciphertext (1568) | sealed`, format [KEM_FORMAT]: the
 * sender encapsulates a fresh secret to the ML-KEM-1024 key of the recipient, and seals the
 * rest, see [MailboxSeal], with `HKDF-SHA256(ikm = kemSecret || sealKey, salt = ciphertext,
 * info = ITEM_INFO)`, 64 bytes. The [sealKey] derives from the X25519 keys of the pair, so
 * an item stays sealed as long as either X25519 or ML-KEM holds, and only the recipient,
 * which holds the ML-KEM private key, opens it.
 */
class MailboxKeys internal constructor(
    private val idKey: ByteArray,
    private val sealKey: ByteArray,
    private val kem: Kem,
    private val cryptoGenerator: CryptoGenerator,
) {
    /** The ML-KEM-1024 key of a direction: the outbox seals, the inbox opens. */
    internal sealed interface Kem {
        /** Seals items to the [publicKey] of the recipient. */
        class Seal(val publicKey: ByteArray) : Kem

        /** Opens the items sealed to this device, with its [privateKey]. */
        class Open(val privateKey: ByteArray) : Kem
    }

    private companion object {
        const val STATE_LABEL = "state"
        const val TAG_LABEL = "tag\n"
        const val TAG_SIZE = MailboxLimits.TAG_LENGTH / 2

        /** The format of an item sealed to an ML-KEM-1024 key. */
        const val KEM_FORMAT: Byte = 1
        const val KEM_CIPHERTEXT_SIZE = 1568
        const val KEM_HEADER_SIZE = 1 + KEM_CIPHERTEXT_SIZE

        /** The HKDF-SHA256 info of the key that seals one item. */
        const val ITEM_INFO = "keyguard/p2p/mailbox-item/v1"
    }

    /** The id of the state item of the direction: `HMAC-SHA256(idKey, "state")`, in hex. */
    val stateId: String = P2pEncoding.hex(cryptoGenerator.hmacSha256(idKey, STATE_LABEL.encodeToByteArray()))

    /**
     * The tag of an item with the [content], everything of it but its timestamps:
     * `HMAC-SHA256(idKey, "tag\n" + SHA-256(content))`, its first 16 bytes in hex. The relay
     * keeps it with the item, so a sender skips posting the same content again and a
     * recipient skips fetching it again.
     */
    fun tag(content: ByteArray): String {
        val digest = cryptoGenerator.hashSha256(content)
        val mac = cryptoGenerator.hmacSha256(idKey, TAG_LABEL.encodeToByteArray() + digest)
        return P2pEncoding.hex(mac.copyOf(TAG_SIZE))
    }

    /** Seals the [plaintext] of an item to the recipient of the outbox. */
    fun seal(plaintext: ByteArray): ByteArray {
        val publicKey = (kem as? Kem.Seal)?.publicKey
        checkNotNull(publicKey) { "The inbox opens items; it does not seal them" }
        val encapsulation = NativeCryptoPrimitives.mlKemEncapsulate(publicKey)
        return withItemKey(encapsulation.sharedSecret, encapsulation.ciphertext) { key ->
            byteArrayOf(KEM_FORMAT) + encapsulation.ciphertext + MailboxSeal.seal(plaintext, key, cryptoGenerator)
        }
    }

    /**
     * Returns the plaintext of the [sealed] item, or `null` if it was not sealed to this
     * device with these keys, was changed since, or opens to more than [maxSize] bytes. An
     * outbox opens nothing: only the recipient holds the ML-KEM private key.
     */
    fun open(sealed: ByteArray, maxSize: Long): ByteArray? {
        val privateKey = (kem as? Kem.Open)?.privateKey
            ?.takeIf { sealed.size > KEM_HEADER_SIZE && sealed[0] == KEM_FORMAT }
            ?: return null
        val ciphertext = sealed.copyOfRange(1, KEM_HEADER_SIZE)
        val body = sealed.copyOfRange(KEM_HEADER_SIZE, sealed.size)
        return decapsulateOrNull(privateKey, ciphertext)?.let { secret ->
            withItemKey(secret, ciphertext) { key ->
                (MailboxSeal.open(body, key, maxSize) as? SealOpening.Opened)?.plaintext
            }
        }
    }

    private fun decapsulateOrNull(privateKey: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        NativeCryptoPrimitives.mlKemDecapsulate(privateKey, ciphertext)
    } catch (expected: NativeCryptoException) {
        null
    }

    /** Runs [block] with the key of one item, then wipes it and the KEM [secret]. */
    private inline fun <T> withItemKey(secret: ByteArray, ciphertext: ByteArray, block: (ByteArray) -> T): T {
        val key = secret.useAndClear { kemSecret ->
            (kemSecret + sealKey).useAndClear { ikm ->
                cryptoGenerator.hkdf(
                    seed = ikm,
                    salt = ciphertext,
                    info = ITEM_INFO.encodeToByteArray(),
                    length = MailboxSeal.KEY_SIZE,
                )
            }
        }
        return key.useAndClear(block)
    }

    override fun toString(): String = "MailboxKeys"
}
