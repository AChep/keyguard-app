package com.artemchep.keyguard.common.service.webauthn

import kotlin.io.encoding.Base64

object PasskeyBase64 {
    private val urlSafeNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val urlSafeWithPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)

    fun encodeToString(
        data: ByteArray,
    ): String = urlSafeNoPadding.encode(data)

    fun encodeToStringPadding(
        data: ByteArray,
    ): String = urlSafeWithPadding.encode(data)

    fun decode(
        data: String,
    ): ByteArray = urlSafeNoPadding.decode(data)
}
