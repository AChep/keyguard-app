package com.artemchep.keyguard.common.model

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
                .toByteArray(),
        )
    }
}
