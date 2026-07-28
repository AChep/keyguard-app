package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptedValue

/**
 * Wraps value fields stored in [Entry].
 */
sealed class EntryValue {
    /**
     * Returns underlying value, decrypting if required.
     */
    abstract val content: String

    /**
     * Checks whether [content] is empty without exposing [EncryptedValue].
     */
    abstract fun isEmpty(): Boolean

    /**
     * Should be used for non-sensitive values.
     */
    data class Plain(
        override val content: String
    ) : EntryValue() {
        override fun isEmpty() = content.isEmpty()
    }

    /**
     * Should be used for secrets.
     */
    data class Encrypted(
        private val value: EncryptedValue
    ) : EntryValue() {
        override val content: String get() = value.text

        override fun isEmpty() = value.byteLength == 0
    }

    /**
     * Replaces wrapped value with result of the [block].
     */
    inline fun map(block: (String) -> String) = when (this) {
        is Plain -> Plain(block(content))
        is Encrypted -> Encrypted(EncryptedValue.fromString(block(content)))
    }
}
