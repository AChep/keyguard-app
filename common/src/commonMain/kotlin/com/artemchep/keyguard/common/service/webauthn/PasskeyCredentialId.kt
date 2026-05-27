package com.artemchep.keyguard.common.service.webauthn

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
        return ByteArray(UUID_SIZE_BYTES)
            .also { bytes ->
                uuid.toLongs { mostSignificantBits, leastSignificantBits ->
                    bytes.writeLong(offset = 0, value = mostSignificantBits)
                    bytes.writeLong(offset = Long.SIZE_BYTES, value = leastSignificantBits)
                }
            }
    }

    fun decode(
        data: ByteArray,
    ): String {
        if (data.size == UUID_SIZE_BYTES) {
            return Uuid.fromLongs(
                data.readLong(offset = 0),
                data.readLong(offset = Long.SIZE_BYTES),
            ).toString()
        }
        return PasskeyBase64.encodeToString(data)
    }

    private fun ByteArray.writeLong(
        offset: Int,
        value: Long,
    ) {
        for (index in 0 until Long.SIZE_BYTES) {
            val shift = Long.SIZE_BITS - Byte.SIZE_BITS * (index + 1)
            this[offset + index] = (value ushr shift).toByte()
        }
    }

    private fun ByteArray.readLong(
        offset: Int,
    ): Long {
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or
                    (this[offset + index].toLong() and 0xffL)
        }
        return value
    }
}
