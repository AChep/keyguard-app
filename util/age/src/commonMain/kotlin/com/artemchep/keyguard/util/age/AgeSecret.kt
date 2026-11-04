package com.artemchep.keyguard.util.age

/** What an age archive is encrypted to. */
sealed interface AgeSecret

/**
 * A strict UTF-8 password, neither trimmed nor normalized. Every archive
 * opened or written with it runs scrypt: 128 MiB and noticeable time on a phone.
 */
class AgePassword(val value: String) : AgeSecret {
    override fun toString() = "AgePassword(redacted)"
}

/** An X25519 identity, `AGE-SECRET-KEY-1…`. Involves no key derivation. */
class AgeIdentity(val value: String) : AgeSecret {
    override fun toString() = "AgeIdentity(redacted)"

    companion object {
        fun generate(): AgeIdentity = AgeSession().use { session ->
            val bytes = session.call(AgeCommand.GENERATE_IDENTITY)
            try {
                AgeIdentity(bytes.decodeToString(throwOnInvalidSequence = true))
            } finally {
                bytes.fill(0)
            }
        }
    }
}

internal fun AgeSecret.encode(): ByteArray = when (this) {
    is AgePassword -> value
    is AgeIdentity -> value
}.encodeToByteArray(throwOnInvalidSequence = true)

internal val AgeSecret.createWriterCommand: Int
    get() = if (this is AgeIdentity) AgeCommand.CREATE_WRITER_KEY else AgeCommand.CREATE_WRITER

internal val AgeSecret.openReaderCommand: Int
    get() = if (this is AgeIdentity) AgeCommand.OPEN_READER_KEY else AgeCommand.OPEN_READER
