package com.artemchep.keyguard.util.zip

import com.artemchep.keyguard.util.zip.bridge.NativeZip
import com.artemchep.keyguard.util.zip.bridge.decodeNativeZipFailure
import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_DOMAIN_BRIDGE
import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_BRIDGE_CODE_WRONG_PASSWORD
import kotlinx.io.Buffer
import kotlinx.io.RawSource

internal actual fun openZipArchiveEntry(bytes: ByteArray, entry: ZipArchiveEntry, password: String?): RawSource {
    NativeZipAbi.ensureCompatible()
    val handle = archiveResult(NativeZip.memoryOpen(bytes, entry.id, entry.offset, password.orEmpty()))
    return object : RawSource {
        private var closed = false
        private val buffer = ByteArray(8192)
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed)
            if (byteCount == 0L) return 0L
            val count = minOf(byteCount, buffer.size.toLong()).toInt()
            val read = archiveResult(NativeZip.memoryRead(handle, buffer, count)).toInt()
            return if (read == 0) -1 else {
                sink.write(buffer, 0, read)
                read.toLong()
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            buffer.fill(0)
            archiveResult(NativeZip.memoryClose(handle))
        }
    }
}

private fun archiveResult(value: Long): Long {
    if (value >= 0L) return value
    val failure = decodeNativeZipFailure(value)
    val reason = if (failure.domain == NATIVE_ZIP_DOMAIN_BRIDGE &&
        failure.rawCode == NATIVE_ZIP_BRIDGE_CODE_WRONG_PASSWORD
    ) ZipArchiveError.Password else ZipArchiveError.Invalid
    throw ZipArchiveException(reason)
}
