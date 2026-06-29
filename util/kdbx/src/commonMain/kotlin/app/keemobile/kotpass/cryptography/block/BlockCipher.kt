package app.keemobile.kotpass.cryptography.block

internal interface BlockCipher {
    val blockSize: Int

    val isEncrypting: Boolean

    fun init(encrypting: Boolean, key: ByteArray)

    fun processBlock(
        src: ByteArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int
    ): Int

    fun reset()
}
