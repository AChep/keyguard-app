package com.artemchep.keyguard.apple.agent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EEXIST
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.geteuid
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.stat

internal fun requireSocketPath(path: String) {
    require(path.startsWith('/') && '\u0000' !in path && path.encodeToByteArray().size <= MAX_UNIX_SOCKET_PATH_BYTES) {
        "The App Group path is too long for an agent socket"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nonblocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL)
    check(flags >= 0 && fcntl(fd, F_SETFL, flags or O_NONBLOCK) == 0 && fcntl(fd, F_SETFD, FD_CLOEXEC) == 0) {
        "Could not configure agent transport"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ensurePrivateDirectory(path: String) {
    require(path.startsWith('/') && '\u0000' !in path)
    if (mkdir(path, PRIVATE_DIRECTORY_PERMISSIONS) != 0 && errno != EEXIST) {
        error("Could not create private agent directory")
    }
    memScoped {
        val info = alloc<stat>()
        check(
            lstat(path, info.ptr) == 0 &&
                info.st_mode.toInt() and S_IFMT == S_IFDIR &&
                info.st_uid == geteuid() &&
                info.st_mode.toInt() and UNIX_PERMISSION_MASK == PRIVATE_DIRECTORY_PERMISSION_BITS,
        ) {
            "Agent directory must be owned by the current user with mode 0700 and must not be a symlink"
        }
    }
}

private const val MAX_UNIX_SOCKET_PATH_BYTES = 103

private const val PRIVATE_DIRECTORY_PERMISSIONS: UShort = 0x1c0u

private const val PRIVATE_DIRECTORY_PERMISSION_BITS = 0x1c0

private const val UNIX_PERMISSION_MASK = 0x1ff
