package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.model.BiometricBindingException

/**
 * Linux persists an opaque handle to a native, process-local credential.
 * The master key is released only by the authenticated native operation,
 * then consumed by the unlock action. No wrapping key enters the JVM.
 */
class LeBiometricCipherLinux private constructor(
    val forEncryption: Boolean,
    private val protect: ((ByteArray) -> ByteArray)?,
    private var handle: ByteArray?,
) : LeBiometricCipher {
    private var plaintext: ByteArray? = null

    @Synchronized
    override fun clear() {
        plaintext?.fill(0)
        plaintext = null
    }

    override val iv: ByteArray
        get() = requireNotNull(handle).copyOf()

    @Synchronized
    fun completeDecryption(secret: ByteArray) {
        check(!forEncryption)
        require(secret.isNotEmpty())
        plaintext?.fill(0)
        plaintext = secret.copyOf()
    }

    @Synchronized
    override fun encode(data: ByteArray): ByteArray {
        if (forEncryption) {
            val protected = requireNotNull(protect)(data)
            handle = protected.copyOf()
            return protected
        }
        val secret = checkNotNull(plaintext) { "System authentication is required." }
        plaintext = null
        if (!data.contentEquals(handle)) {
            secret.fill(0)
            throw BiometricBindingException("Linux unlock credential does not match.")
        }
        // Transfer ownership to MasterKey; this cipher retains no plaintext
        // after the unlock action, including when its closure remains cached.
        return secret
    }

    companion object {
        private val PREFIX = byteArrayOf('K'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte(), 1)
        private const val HANDLE_RANDOM_SIZE = 32

        fun forEncryption(protect: (ByteArray) -> ByteArray) =
            LeBiometricCipherLinux(true, protect, null)

        fun forDecryption(handle: ByteArray): LeBiometricCipherLinux {
            if (
                handle.size != PREFIX.size + HANDLE_RANDOM_SIZE ||
                !handle.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)
            ) {
                throw BiometricBindingException("Invalid Linux unlock credential.")
            }
            return LeBiometricCipherLinux(false, null, handle.copyOf())
        }
    }
}
