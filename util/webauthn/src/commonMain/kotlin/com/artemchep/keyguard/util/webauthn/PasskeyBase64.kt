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
}
