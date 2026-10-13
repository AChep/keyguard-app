package com.artemchep.keyguard.util.webauthn

import kotlin.io.encoding.Base64

object PasskeyBase64 {
    private val urlSafeNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val urlSafeWithPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)
    private val urlSafeOptionalPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encodeToString(
        data: ByteArray,
    ): String = urlSafeNoPadding.encode(data)

    fun encodeToStringPadding(
        data: ByteArray,
    ): String = urlSafeWithPadding.encode(data)

    fun decode(
        data: String,
    ): ByteArray = urlSafeNoPadding.decode(data)

    /** Vault storage may contain standard Base64 or unpadded Base64url keys. */
    fun decodeStoredKeyOrNull(data: String): ByteArray? = try {
        urlSafeOptionalPadding.decode(data.replace('+', '-').replace('/', '_'))
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A stored user handle in either Base64 alphabet, with or without padding. `null` if it
     * is unreadable, empty, or longer than the 64 bytes WebAuthn allows.
     */
    fun decodeUserHandleOrNull(data: String): ByteArray? = data
        .takeIf { it.length <= MAX_ENCODED_USER_HANDLE_CHARS }
        ?.let(::decodeStoredKeyOrNull)
        ?.takeIf { it.size in 1..MAX_USER_HANDLE_BYTES }
}

/** https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialuserentity-id */
private const val MAX_USER_HANDLE_BYTES = 64

/** Padded Base64 of [MAX_USER_HANDLE_BYTES]. */
private const val MAX_ENCODED_USER_HANDLE_CHARS = 88
