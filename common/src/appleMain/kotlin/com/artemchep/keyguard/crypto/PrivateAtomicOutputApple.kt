package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.close
import platform.posix.fstat
import platform.posix.mkstemp
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun createPrivateTemporarySibling(
    destination: LocalPath,
): LocalPath = memScoped {
    val pathTemplate = "${destination.value}.keyguard-private-XXXXXX"
    val mutablePathTemplate = pathTemplate.cstr.getPointer(this)
    val descriptor = mkstemp(mutablePathTemplate)
    check(descriptor >= 0) {
        "Could not create private atomic output"
    }

    val path = mutablePathTemplate.toKString()
    var keepFile = false
    var descriptorOpen = true
    try {
        val status = alloc<platform.posix.stat>()
        check(fstat(descriptor, status.ptr) == 0) {
            "Could not inspect private atomic output permissions"
        }
        check(status.st_mode.toInt() and FILE_PERMISSION_MASK == OWNER_READ_WRITE) {
            "Private atomic output does not use owner-only permissions"
        }
        val closeResult = close(descriptor)
        descriptorOpen = false
        check(closeResult == 0) {
            "Could not close private atomic output"
        }
        keepFile = true
        LocalPath(path)
    } finally {
        if (!keepFile) {
            if (descriptorOpen) close(descriptor)
            unlink(path)
        }
    }
}

private const val FILE_PERMISSION_MASK = 0x1FF
private const val OWNER_READ_WRITE = 0x180
