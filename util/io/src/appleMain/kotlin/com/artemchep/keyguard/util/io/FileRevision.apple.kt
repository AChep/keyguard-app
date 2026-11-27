package com.artemchep.keyguard.util.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.IOException
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.errno
import platform.posix.stat

private const val NANOS_PER_SECOND = 1_000_000_000L

@OptIn(ExperimentalForeignApi::class)
actual fun LocalPath.fileRevisionOrNull(): FileRevision? = memScoped {
    val info = alloc<stat>()
    if (stat(value, info.ptr) != 0) {
        val code = errno
        if (code == ENOENT || code == ENOTDIR) {
            return null
        }
        throw IOException("Failed to read the file metadata, errno $code")
    }
    val modified = info.st_mtimespec
    FileRevision(
        modifiedNanos = modified.tv_sec.convert<Long>() * NANOS_PER_SECOND + modified.tv_nsec.convert<Long>(),
        size = info.st_size.convert(),
        identity = "${info.st_dev}:${info.st_ino}",
    )
}
