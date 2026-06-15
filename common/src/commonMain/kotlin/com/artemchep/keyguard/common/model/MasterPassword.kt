package com.artemchep.keyguard.common.model

import kotlin.jvm.JvmInline

@JvmInline
value class MasterPassword(
    val byteArray: ByteArray,
) {
    companion object {
        fun of(
            password: String,
        ) = MasterPassword(
            byteArray = password
                .trim()
                .encodeToByteArray(),
        )
    }
}
