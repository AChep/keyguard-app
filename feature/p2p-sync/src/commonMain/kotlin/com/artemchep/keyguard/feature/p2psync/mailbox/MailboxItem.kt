package com.artemchep.keyguard.feature.p2psync.mailbox

import com.artemchep.keyguard.feature.p2psync.model.EnvelopeDecoding
import com.artemchep.keyguard.feature.p2psync.model.Roster

/**
 * The kinds of the items the devices of a sync group leave on the relay for each other,
 * while the others are offline. The relay keeps each item under an id only the devices
 * derive, sealed, and sees neither what it holds nor whose it is; the kind only sets its
 * size limit and the order the relay evicts items in.
 *
 * @property wireName the name of the kind in the relay API and in the items.
 */
enum class MailboxKind(
    val wireName: String,
) {
    /**
     * The latest state of the sender for the recipient: its roster, its envelope and the
     * blobs it misses. Each direction of a pair has one, which each post replaces.
     */
    STATE("state"),

    /**
     * One blob of the group, such as the words of a wordlist: stored once for every device
     * of the group, under an id and with a key that derive from the group key and the hash
     * of the blob. Any device that holds it writes it once, and any device that needs it
     * fetches it.
     */
    BLOB("blob"),
}

/** The limits of the items of [MailboxCodec]. */
object MailboxLimits {
    /** The format version of the items this build writes and reads. */
    const val VERSION: Int = 1

    /** 1 MiB, the largest sealed state item the relay takes. */
    const val MAX_STATE_BYTES: Int = 1_048_576

    /** 17 MiB, the largest sealed blob item the relay takes: a blob of 16 MiB and its seal. */
    const val MAX_BLOB_BYTES: Int = 17_825_792

    /** 7 days, the longest an item waits on the relay; the relay may keep it shorter. */
    const val TTL_SECONDS: Long = 7L * 24L * 60L * 60L

    /** The most blobs a state item says its sender misses. A reader takes the first ones and ignores the rest. */
    const val MAX_MISSING: Int = 64

    /** The characters of an item id: 32 bytes in lowercase hex. */
    const val ITEM_ID_LENGTH: Int = 64

    /** The characters of a tag: 16 bytes in lowercase hex. */
    const val TAG_LENGTH: Int = 32
}

/**
 * Where a state item goes: the [item] id on the relay, the device it is [from] and the
 * device it is [to]. They are sealed inside the item, so the relay can not move it to
 * another pair or direction: the recipient checks them against the id it asked for and the
 * pair.
 */
data class MailboxAddress(
    val item: String,
    val from: String,
    val to: String,
)

/**
 * A state item as its recipient opened it.
 *
 * @property expiresAt when it stops counting, in epoch milliseconds.
 * @property roster the roster of the sender, as it sent it; validate it before use.
 * @property envelope the envelope of the sender, or `null` if it sent only its roster, such
 *   as once it turned relay storage off, or one this device does not read.
 * @property missing the blobs the sender needs and did not find on the relay: a device
 *   that holds one leaves it there.
 */
class MailboxState(
    val address: MailboxAddress,
    val expiresAt: Long,
    val roster: Roster,
    val envelope: EnvelopeDecoding.Valid?,
    val missing: List<String>,
)

/** What [MailboxCodec] made of an opened item. */
sealed interface MailboxOpening<out T> {
    /** The item is the one asked for, and still counts. */
    class Valid<T>(val item: T) : MailboxOpening<T>

    /** The item is refused for the [reason]. */
    data class Rejected(val reason: MailboxRejection) : MailboxOpening<Nothing>
}

/** Why [MailboxCodec] refused an item. */
enum class MailboxRejection {
    /** It is not an item of this format. */
    MALFORMED,

    /** It is an item of another format version: one of the devices needs an update. */
    UNSUPPORTED_VERSION,

    /** It holds more than this device takes. */
    TOO_LARGE,

    /** It names another item id or kind than the one asked for: the relay moved it. */
    WRONG_ITEM,

    /** It is from another device than the peer. */
    WRONG_SENDER,

    /** It is for another device than this one. */
    WRONG_RECIPIENT,

    /** It expired: the relay kept, or handed over, an old one. */
    EXPIRED,

    /** Its blob is not the one asked for, or its bytes do not match its hash. */
    WRONG_HASH,
}
