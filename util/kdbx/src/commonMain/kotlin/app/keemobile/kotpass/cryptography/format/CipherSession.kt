package app.keemobile.kotpass.cryptography.format

interface CipherSession : AutoCloseable {
    fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray

    fun finish(): ByteArray

    override fun close()
}
