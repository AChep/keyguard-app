package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.BYTE_BITS
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.writeBigEndian

/**
 * The code one device shows (as a QR code and as text) and the other scans to start pairing.
 *
 * Its bytes, numbers big-endian:
 *
 * ```
 * magic (u8) | version (u8) | flags (u8) | psk (32) | pub (32) | expiry (u32) |
 * relay (u16 length + UTF-8) | ice (u16 length + UTF-8) | extensions
 * ```
 *
 * The flags hold [member] in bit 0 and the [SyncScope.codeId] of the [scope] in bits 1 to 3;
 * the other bits are zero, and a reader refuses a code with any other. A URL of length 0 is
 * the default one. A code without URLs is [MIN_SIZE] bytes. The [version] is the only
 * version of a code: a newer one may change everything after it.
 *
 * The extensions run to the end, at most [MAX_EXTENSIONS_SIZE] bytes, each
 * `type (u8) | length (u16) | value`. A reader skips one it does not know, unless its type
 * has the [EXTENSION_CRITICAL] bit: then a newer build made the code. No extension is
 * defined yet, so this build writes none.
 *
 * The text form is [PREFIX] and the uppercase RFC 4648 base32 of the bytes, without
 * padding: a QR code holds it in its alphanumeric mode, denser than its byte mode.
 *
 * @property psk the one-time pre-shared key of the Noise XXpsk3 handshake.
 * @property pub the Noise static public key of the displaying device; the scanner pins it.
 * @property member `true` if the displaying device is already a member of the sync group.
 * @property expiresAt when the code expires, in seconds since the epoch.
 * @property scope the scope of the sync group the code pairs into.
 * @property relay the relay URL, if not the default one.
 * @property ice the ICE servers URL, if not the default one.
 */
class PairingCode(
    val psk: ByteArray,
    val pub: ByteArray,
    val member: Boolean,
    val expiresAt: Long,
    val scope: SyncScope,
    relay: String? = null,
    ice: String? = null,
) {
    val relay: String? = relay?.takeIf { it.isNotEmpty() }

    val ice: String? = ice?.takeIf { it.isNotEmpty() }

    init {
        require(psk.size == KEY_SIZE) { "A pre-shared key is $KEY_SIZE bytes." }
        require(pub.size == KEY_SIZE) { "A static key is $KEY_SIZE bytes." }
        require(expiresAt in 0..MAX_EXPIRY) { "The expiry does not fit a code." }
        require(urlSize(this.relay) <= MAX_URL_SIZE && urlSize(this.ice) <= MAX_URL_SIZE) {
            "A URL of a code is at most $MAX_URL_SIZE bytes."
        }
    }

    /** When the code expires, in milliseconds since the epoch. */
    val expiresAtMillis: Long get() = expiresAt * MILLIS_PER_SECOND

    /** `true` once [nowMillis] reached the expiry. */
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /** Returns the bytes of the code. */
    fun toByteArray(): ByteArray {
        val relayBytes = relay?.encodeToByteArray() ?: ByteArray(0)
        val iceBytes = ice?.encodeToByteArray() ?: ByteArray(0)
        val out = ByteArray(MIN_SIZE + relayBytes.size + iceBytes.size)
        var offset = 0
        fun putNumber(size: Int, value: Long) {
            out.writeBigEndian(offset, size, value)
            offset += size
        }

        fun putBytes(bytes: ByteArray) {
            bytes.copyInto(out, offset)
            offset += bytes.size
        }
        putNumber(1, MAGIC.toLong())
        putNumber(1, VERSION.toLong())
        putNumber(1, flags(member, scope).toLong())
        putBytes(psk)
        putBytes(pub)
        putNumber(EXPIRY_SIZE, expiresAt)
        putNumber(URL_LENGTH_SIZE, relayBytes.size.toLong())
        putBytes(relayBytes)
        putNumber(URL_LENGTH_SIZE, iceBytes.size.toLong())
        putBytes(iceBytes)
        return out
    }

    /** Returns the text form: [PREFIX] followed by the uppercase base32 of the bytes. */
    fun encode(): String = PREFIX + P2pEncoding.base32UpperCase(toByteArray())

    override fun equals(other: Any?): Boolean = this === other ||
        other is PairingCode &&
        psk.contentEquals(other.psk) &&
        pub.contentEquals(other.pub) &&
        member == other.member &&
        expiresAt == other.expiresAt &&
        scope == other.scope &&
        relay == other.relay &&
        ice == other.ice

    override fun hashCode(): Int {
        var result = psk.contentHashCode()
        result = HASH_FACTOR * result + pub.contentHashCode()
        result = HASH_FACTOR * result + member.hashCode()
        result = HASH_FACTOR * result + expiresAt.hashCode()
        result = HASH_FACTOR * result + scope.hashCode()
        result = HASH_FACTOR * result + relay.hashCode()
        result = HASH_FACTOR * result + ice.hashCode()
        return result
    }

    /** Leaves the pre-shared key out: it admits a device. */
    override fun toString(): String =
        "PairingCode(member=$member, scope=$scope, expiresAt=$expiresAt, relay=$relay, ice=$ice)"

    companion object {
        /** The first byte of every code. */
        const val MAGIC: Int = 0x4B

        /** The code format this build writes and reads. */
        const val VERSION: Int = 1

        /** The prefix of the text form; it is in the QR alphanumeric set. */
        const val PREFIX: String = "KGP:"

        /** The size of the pre-shared key and of the static key, in bytes. */
        const val KEY_SIZE: Int = 32

        /** The size of a code without URLs, in bytes. */
        const val MIN_SIZE: Int = 3 + 2 * KEY_SIZE + 4 + 2 + 2

        /** The largest URL of a code, in UTF-8 bytes. */
        const val MAX_URL_SIZE: Int = 1024

        /** The most bytes of the extensions of a code, their types and lengths included. */
        const val MAX_EXTENSIONS_SIZE: Int = 256

        /** The bit of an extension type that marks it critical: a reader that does not know it refuses the code. */
        const val EXTENSION_CRITICAL: Int = 0x80

        /**
         * The longest text of a code: the prefix and the base32 of a code with two URLs of
         * [MAX_URL_SIZE] bytes and [MAX_EXTENSIONS_SIZE] bytes of extensions. Longer text is
         * never decoded.
         */
        const val MAX_TEXT_LENGTH: Int =
            PREFIX.length + ((MIN_SIZE + 2 * MAX_URL_SIZE + MAX_EXTENSIONS_SIZE) * BYTE_BITS + 4) / 5

        /** The latest expiry a code holds, in seconds since the epoch: an unsigned 32-bit number. */
        const val MAX_EXPIRY: Long = 0xFFFF_FFFFL

        private const val FLAG_MEMBER = 1

        private const val SCOPE_SHIFT = 1

        private const val SCOPE_MASK = 0b111

        private const val EXPIRY_SIZE = 4

        private const val URL_LENGTH_SIZE = 2

        private const val MILLIS_PER_SECOND = 1_000L

        private const val HASH_FACTOR = 31

        private fun urlSize(url: String?): Int = url?.encodeToByteArray()?.size ?: 0

        private fun flags(member: Boolean, scope: SyncScope): Int =
            (if (member) FLAG_MEMBER else 0) or (scope.codeId shl SCOPE_SHIFT)

        /**
         * Reads a code from its text form, see [encode]. The base32 may be of either case;
         * surrounding whitespace is ignored.
         *
         * @param expected the scope the code must pair into, or `null` to accept any.
         */
        fun decode(
            text: String,
            expected: SyncScope? = null,
        ): PairingCodeDecoding {
            val trimmed = text.trim()
            val decoding = when {
                // Pasted text reaches here: never decode more than a code can be.
                trimmed.length > MAX_TEXT_LENGTH -> invalid(PairingCodeError.MALFORMED)
                !trimmed.startsWith(PREFIX, ignoreCase = true) -> invalid(PairingCodeError.WRONG_PREFIX)
                else -> P2pEncoding.base32OrNull(trimmed.substring(PREFIX.length))
                    ?.let(::fromByteArray)
                    ?: invalid(PairingCodeError.MALFORMED)
            }
            val scope = (decoding as? PairingCodeDecoding.Valid)?.code?.scope
            return if (scope != null && expected != null && scope != expected) {
                invalid(PairingCodeError.WRONG_SCOPE)
            } else {
                decoding
            }
        }

        /** Reads a code from its [bytes], see [toByteArray]. */
        fun fromByteArray(bytes: ByteArray): PairingCodeDecoding {
            val reader = CodeReader(bytes)
            val magic = reader.number(1).toInt()
            val version = reader.number(1).toInt()
            return when {
                !reader.valid || magic != MAGIC -> invalid(PairingCodeError.MALFORMED)
                version > VERSION -> invalid(PairingCodeError.NEWER_VERSION)
                version < VERSION -> invalid(PairingCodeError.OLDER_VERSION)
                else -> reader.readCode()
            }
        }

        /** Reads what follows the version of a code of this [VERSION]. */
        private fun CodeReader.readCode(): PairingCodeDecoding {
            val flags = number(1).toInt()
            val psk = bytes(KEY_SIZE)
            val pub = bytes(KEY_SIZE)
            val expiresAt = number(EXPIRY_SIZE)
            val relay = url()
            val ice = url()
            val critical = extensions()
            val scope = SyncScope.ofCodeId((flags shr SCOPE_SHIFT) and SCOPE_MASK)
            val unknownFlags = flags and (FLAG_MEMBER or (SCOPE_MASK shl SCOPE_SHIFT)).inv()
            return when {
                !valid || unknownFlags != 0 -> invalid(PairingCodeError.MALFORMED)
                // An extension this build does not know, and must.
                critical -> invalid(PairingCodeError.NEWER_VERSION)
                // A scope this build does not know: a kind of pairing it does not offer.
                scope == null -> invalid(PairingCodeError.WRONG_SCOPE)
                else -> PairingCodeDecoding.Valid(
                    PairingCode(
                        psk = psk,
                        pub = pub,
                        member = (flags and FLAG_MEMBER) != 0,
                        expiresAt = expiresAt,
                        scope = scope,
                        relay = relay,
                        ice = ice,
                    ),
                )
            }
        }

        private fun invalid(error: PairingCodeError) = PairingCodeDecoding.Invalid(error)
    }
}

/**
 * Reads the fields of a code, one after another. A field past the end, a URL that is too
 * long or not UTF-8, or too many bytes of extensions, reads as zeros and makes the code
 * not [valid].
 */
private class CodeReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    /** `false` once a field could not be read. */
    var valid: Boolean = true
        private set

    val isAtEnd: Boolean get() = offset == bytes.size

    fun bytes(size: Int): ByteArray {
        val fits = bytes.size - offset >= size
        if (!fits) valid = false
        val field = if (fits) bytes.copyOfRange(offset, offset + size) else ByteArray(size)
        offset = if (fits) offset + size else bytes.size
        return field
    }

    fun number(size: Int): Long = bytes(size).readBigEndian(0, size)

    /** A URL: its length, then its UTF-8 bytes; `null` for the default one. */
    fun url(): String? {
        val size = number(2).toInt()
        if (size > PairingCode.MAX_URL_SIZE) valid = false
        val utf8 = bytes(size.coerceAtMost(PairingCode.MAX_URL_SIZE))
        val text = try {
            utf8.decodeToString(throwOnInvalidSequence = true)
        } catch (expected: CharacterCodingException) {
            valid = false
            null
        }
        return text?.takeIf { it.isNotEmpty() }
    }

    /**
     * The extensions, up to the end: each a type, a `u16` length and a value. Returns `true`
     * if the type of one has the [PairingCode.EXTENSION_CRITICAL] bit; this build knows no
     * extension, so it skips the others.
     */
    fun extensions(): Boolean {
        if (bytes.size - offset > PairingCode.MAX_EXTENSIONS_SIZE) {
            valid = false
            offset = bytes.size
        }
        var critical = false
        while (!isAtEnd) {
            val type = number(1).toInt()
            val size = number(2).toInt()
            bytes(size.coerceAtMost(PairingCode.MAX_EXTENSIONS_SIZE))
            if (type and PairingCode.EXTENSION_CRITICAL != 0) critical = true
        }
        return critical
    }
}

/** Why a pairing code could not be read. */
enum class PairingCodeError {
    /** The text is not a Keyguard pairing code. */
    WRONG_PREFIX,

    /** A newer build made the code: this device needs an update. */
    NEWER_VERSION,

    /** An older build made the code: the other device needs an update. */
    OLDER_VERSION,

    /** The code is damaged. */
    MALFORMED,

    /** The code pairs into another kind of sync group, see [SyncScope]. */
    WRONG_SCOPE,
}

/** The result of [PairingCode.decode]. */
sealed interface PairingCodeDecoding {
    /** The text held a valid [code]. */
    data class Valid(val code: PairingCode) : PairingCodeDecoding

    /** The text could not be read because of [error]. */
    data class Invalid(val error: PairingCodeError) : PairingCodeDecoding
}
