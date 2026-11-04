package com.artemchep.keyguard.util.age

internal const val AGE_ABI_VERSION = 1
internal const val AGE_CHUNK_BYTES = 64 * 1024
internal const val AGE_MAX_RESPONSE = AGE_CHUNK_BYTES + 1

internal expect object NativeAge {
    fun create(): Long
    fun execute(handle: Long, request: ByteArray): ByteArray
    fun close(handle: Long)
}

/** Commands of native ABI v1. */
internal object AgeCommand {
    const val CREATE_WRITER = 1
    const val BEGIN_ENTRY = 2
    const val WRITE = 3
    const val END_ENTRY = 4
    const val FINISH = 5
    const val DRAIN = 6
    const val OPEN_READER = 7
    const val NEXT_ENTRY = 8
    const val READ = 9
    const val VERIFY = 10
    const val SPOOL = 11
    const val SPOOL_WRITE = 12
    const val CREATE_WRITER_KEY = 13
    const val OPEN_READER_KEY = 14
    const val GENERATE_IDENTITY = 15
}
