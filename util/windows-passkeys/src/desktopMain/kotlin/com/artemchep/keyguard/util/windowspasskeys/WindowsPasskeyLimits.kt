package com.artemchep.keyguard.util.windowspasskeys

/**
 * What the native bridge accepts in a [WindowsPasskeyIdentity]. One entry outside these
 * bounds fails a whole sync, so check every entry before syncing. A test compares these
 * with the native values.
 */
object WindowsPasskeyLimits {
    const val MAX_CREDENTIAL_ID_BYTES = 1023
    const val MAX_USER_ID_BYTES = 64

    /** UTF-8 bytes of any text: names and the RP ID. */
    const val MAX_TEXT_BYTES = 4096

    fun acceptsCredentialId(id: ByteArray) = id.size in 1..MAX_CREDENTIAL_ID_BYTES

    fun acceptsUserId(id: ByteArray) = id.size in 1..MAX_USER_ID_BYTES

    /** Windows strings end at NUL, so they cannot contain one. */
    fun acceptsText(value: String) = '\u0000' !in value && value.encodeToByteArray().size <= MAX_TEXT_BYTES
}
