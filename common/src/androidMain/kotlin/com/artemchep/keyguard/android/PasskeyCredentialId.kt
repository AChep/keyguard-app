package com.artemchep.keyguard.android

import java.nio.ByteBuffer
import kotlin.uuid.Uuid

object PasskeyCredentialId {
    private const val UUID_SIZE_BYTES = Long.SIZE_BYTES * 2

    fun encode(
        credentialId: String,
    ): ByteArray {
        val uuid = kotlin
            .runCatching {
                Uuid.parse(credentialId)
            }
            // The credential id is not conforming to UUID rules,
            // this is fine for us (although should not happen), we
            // just return data as if it was encoded with base64.
            .getOrElse {
                return PasskeyBase64.decode(credentialId)
            }
        return ByteBuffer.allocate(UUID_SIZE_BYTES)
            .apply {
                uuid.toLongs { m, l ->
                    putLong(m)
                    putLong(l)
                    Unit
                }
            }
            .array()
    }

    fun decode(
        data: ByteArray,
    ): String {
        if (data.size == UUID_SIZE_BYTES) {
            val bb = ByteBuffer.wrap(data)
            return Uuid.fromLongs(
                bb.getLong(),
                bb.getLong(),
            ).toString()
        }
        return PasskeyBase64.encodeToString(data)
    }
}
