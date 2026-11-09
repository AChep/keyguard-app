package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.feature.p2psync.transport.Decompressed
import com.artemchep.keyguard.util.gzip.GzipError
import com.artemchep.keyguard.util.gzip.GzipException
import com.artemchep.keyguard.util.gzip.gunzip
import com.artemchep.keyguard.util.gzip.gzip

/** The gzip [Compressor] of envelopes, over okio on every platform. */
object GzipCompressor : Compressor {
    override fun compress(bytes: ByteArray): ByteArray = bytes.gzip()

    override fun decompress(bytes: ByteArray, maxSize: Int): Decompressed = try {
        // Stops a gzip bomb before it fills the memory.
        Decompressed.Bytes(bytes.gunzip(maxSize = maxSize.toLong()))
    } catch (e: GzipException) {
        when (e.reason) {
            GzipError.TooLarge -> Decompressed.TooLarge
            GzipError.Invalid -> Decompressed.Invalid
        }
    }
}
