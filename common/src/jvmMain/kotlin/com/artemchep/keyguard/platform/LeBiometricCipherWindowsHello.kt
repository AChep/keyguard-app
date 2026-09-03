package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.model.BiometricBindingException

/**
 * AES cipher whose key is wrapped by a Windows Hello protected key. The
 * persisted [iv] is a payload that carries both the AES IV and the wrapped
 * key, so a decrypt cipher can be restored from it alone.
 */
class LeBiometricCipherWindowsHello private constructor(
    forEncryption: Boolean,
    aesIv: ByteArray,
    private var wrappedSecret: ByteArray?,
    private var secretToWrap: ByteArray?,
) : LeBiometricCipherNative(forEncryption) {
    init {
        _iv = aesIv
    }

    override val iv: ByteArray
        get() = serializePayload(
            aesIv = requireNotNull(_iv),
            wrappedSecret = requireNotNull(wrappedSecret) {
                "Windows Hello cipher has not been materialized."
            },
        )

    fun copyWrappedSecret(): ByteArray = requireNotNull(wrappedSecret) {
        "Wrapped Windows Hello secret is missing."
    }.copyOf()

    fun copySecretToWrap(): ByteArray = requireNotNull(secretToWrap) {
        "Windows Hello secret is missing."
    }.copyOf()

    fun completeEncryption(wrappedSecret: ByteArray) {
        check(forEncryption) {
            "A decryption cipher cannot complete encryption."
        }
        val secret = requireNotNull(secretToWrap) {
            "Windows Hello cipher has already been materialized or discarded."
        }
        try {
            val wrappedSecretCopy = wrappedSecret.copyOf()
            val keyCopy = secret.copyOf()
            _key?.fill(0)
            _key = keyCopy
            this.wrappedSecret?.fill(0)
            this.wrappedSecret = wrappedSecretCopy
        } finally {
            clearSecretToWrap()
        }
    }

    fun completeDecryption(unwrappedSecret: ByteArray) {
        check(!forEncryption) {
            "An encryption cipher cannot complete decryption."
        }
        require(unwrappedSecret.size == AES_KEY_SIZE_BYTES) {
            "Windows Hello returned an invalid secret."
        }
        val keyCopy = unwrappedSecret.copyOf()
        _key?.fill(0)
        _key = keyCopy
    }

    internal fun clearSecretToWrap() {
        secretToWrap?.fill(0)
        secretToWrap = null
    }

    companion object {
        private val MAGIC = byteArrayOf(
            'K'.code.toByte(),
            'G'.code.toByte(),
            'W'.code.toByte(),
            'H'.code.toByte(),
        )
        private const val VERSION: Byte = 1
        private const val AES_KEY_SIZE_BYTES = 32
        private const val AES_IV_SIZE_BYTES = 16
        private const val WRAPPED_LENGTH_SIZE_BYTES = 2
        private const val HEADER_SIZE = 4 + 1 + WRAPPED_LENGTH_SIZE_BYTES + AES_IV_SIZE_BYTES
        private const val MAX_WRAPPED_SECRET_SIZE = 4096

        fun forEncryption(
            secret: ByteArray,
            aesIv: ByteArray,
        ): LeBiometricCipherWindowsHello {
            require(secret.size == AES_KEY_SIZE_BYTES)
            require(aesIv.size == AES_IV_SIZE_BYTES)
            return LeBiometricCipherWindowsHello(
                forEncryption = true,
                aesIv = aesIv.copyOf(),
                wrappedSecret = null,
                secretToWrap = secret.copyOf(),
            )
        }

        @Throws(BiometricBindingException::class)
        fun forDecryption(payload: ByteArray): LeBiometricCipherWindowsHello {
            val parsed = parsePayload(payload)
            return LeBiometricCipherWindowsHello(
                forEncryption = false,
                aesIv = parsed.aesIv,
                wrappedSecret = parsed.wrappedSecret,
                secretToWrap = null,
            )
        }

        private fun serializePayload(
            aesIv: ByteArray,
            wrappedSecret: ByteArray,
        ): ByteArray {
            require(wrappedSecret.isNotEmpty() && wrappedSecret.size <= MAX_WRAPPED_SECRET_SIZE)
            val result = ByteArray(HEADER_SIZE + wrappedSecret.size)
            MAGIC.copyInto(result)
            result[4] = VERSION
            result[5] = (wrappedSecret.size ushr 8).toByte()
            result[6] = wrappedSecret.size.toByte()
            aesIv.copyInto(result, destinationOffset = 7)
            wrappedSecret.copyInto(result, destinationOffset = HEADER_SIZE)
            return result
        }

        @Throws(BiometricBindingException::class)
        private fun parsePayload(payload: ByteArray): ParsedPayload {
            fun invalid(): Nothing = throw BiometricBindingException(
                message = "Invalid Windows Hello cipher payload.",
            )

            if (payload.size < HEADER_SIZE) invalid()
            if (!payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) invalid()
            if (payload[4] != VERSION) invalid()
            val wrappedLengthHighByte = payload[5].toInt() and 0xff
            val wrappedLengthLowByte = payload[6].toInt() and 0xff
            val wrappedLength = (wrappedLengthHighByte shl 8) or wrappedLengthLowByte
            if (
                wrappedLength <= 0 ||
                wrappedLength > MAX_WRAPPED_SECRET_SIZE ||
                payload.size != HEADER_SIZE + wrappedLength
            ) {
                invalid()
            }
            return ParsedPayload(
                aesIv = payload.copyOfRange(7, HEADER_SIZE),
                wrappedSecret = payload.copyOfRange(HEADER_SIZE, payload.size),
            )
        }

        private data class ParsedPayload(
            val aesIv: ByteArray,
            val wrappedSecret: ByteArray,
        )
    }
}
