package app.keemobile.kotpass.database

import app.keemobile.kotpass.io.MAX_DECOMPRESSED_SIZE

data class KdbxReadLimits(
    val maximumBlockSize: Int = ContentBlocks.DEFAULT_MAXIMUM_BLOCK_SIZE,
    val maximumContentBytes: Long = MAX_DECOMPRESSED_SIZE,
) {
    init {
        require(maximumBlockSize > 0) { "Maximum KDBX block size must be positive" }
        require(maximumContentBytes > 0L) { "Maximum KDBX content size must be positive" }
    }

    companion object {
        val Default = KdbxReadLimits()
    }
}
