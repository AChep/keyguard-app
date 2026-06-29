package app.keemobile.kotpass.cryptography.padding

internal sealed interface BlockCipherPadding {
    fun addPadding(input: ByteArray, offset: Int): Int

    fun padCount(input: ByteArray): Int
}
