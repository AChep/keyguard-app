package com.artemchep.keyguard.common.model

import kotlin.jvm.JvmInline

@JvmInline
value class MasterKdfVersion(
    val raw: Int,
) {
    companion object {
        /** PBKDF2, see [MasterKdf.Pbkdf2Legacy]. */
        val V0 = MasterKdfVersion(raw = 0)

        /** Argon2id with the parameters of [MasterKdf.Argon2id.V1]. */
        val V1 = MasterKdfVersion(raw = 1)

        /** Argon2id with custom parameters, see [MasterKdf.Argon2id]. */
        val V2 = MasterKdfVersion(raw = 2)

        fun fromRaw(raw: Int) = MasterKdfVersion(raw = raw)
    }
}
