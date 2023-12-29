package com.artemchep.keyguard.android

import android.util.Base64

object PasskeyBase64 {
    private const val flags = Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE

    fun encodeToString(
        data: ByteArray,
    ) = Base64.encodeToString(data, flags)

    fun encodeToStringPadding(
        data: ByteArray,
    ) = Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE)

    fun decode(
        data: String,
    ) = Base64.decode(data, flags)
}
