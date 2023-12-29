package com.artemchep.keyguard.provider.bitwarden.model

data class PasswordResult(
    val masterKey: ByteArray,
    val passwordKey: ByteArray,
    val encryptionKey: ByteArray,
    val macKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PasswordResult

        if (!masterKey.contentEquals(other.masterKey)) return false
        if (!passwordKey.contentEquals(other.passwordKey)) return false
        if (!encryptionKey.contentEquals(other.encryptionKey)) return false
        if (!macKey.contentEquals(other.macKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = masterKey.contentHashCode()
        result = 31 * result + passwordKey.contentHashCode()
        result = 31 * result + encryptionKey.contentHashCode()
        result = 31 * result + macKey.contentHashCode()
        return result
    }
}
