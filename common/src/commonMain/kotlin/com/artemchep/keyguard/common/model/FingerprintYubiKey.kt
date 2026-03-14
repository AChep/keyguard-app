package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor

data class FingerprintYubiKey(
    val slot: Int,
    val challenge: ByteArray,
    val hkdfSalt: ByteArray,
    val encryptedMasterKey: String,
    val cipherType: CipherEncryptor.Type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FingerprintYubiKey

        if (slot != other.slot) return false
        if (!challenge.contentEquals(other.challenge)) return false
        if (!hkdfSalt.contentEquals(other.hkdfSalt)) return false
        if (encryptedMasterKey != other.encryptedMasterKey) return false
        if (cipherType != other.cipherType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = slot
        result = 31 * result + challenge.contentHashCode()
        result = 31 * result + hkdfSalt.contentHashCode()
        result = 31 * result + encryptedMasterKey.hashCode()
        result = 31 * result + cipherType.hashCode()
        return result
    }
}

const val YUBIKEY_UNLOCK_HKDF_INFO = "keyguard-yubikey-unlock"
