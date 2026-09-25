package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.util.io.LocalPath

/** Filesystem operations whose path, link, or failure semantics differ by platform. */
internal interface PendingUploadFileSystem {
    fun list(directory: LocalPath): List<LocalPath>

    fun isRegularFile(path: LocalPath): Boolean

    fun normalizePath(path: String): String

    fun deleteIfExists(path: LocalPath)
}
