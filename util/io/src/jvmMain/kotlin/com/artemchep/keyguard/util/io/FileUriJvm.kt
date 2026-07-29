package com.artemchep.keyguard.util.io

import java.io.File
import java.net.URI

actual fun LocalPath.toFileUriString(): String =
    toJavaFile().toURI().toString()

actual fun String.toLocalPathFromFileUriOrNull(): LocalPath? = runCatching {
    val uri = URI(this)
    require(uri.scheme?.equals("file", ignoreCase = true) == true)

    File(uri.toLocalFileUri()).toLocalPath()
}.getOrNull()

private fun URI.toLocalFileUri(): URI {
    val authority = authority
    require(authority == null || authority.equals("localhost", ignoreCase = true))
    if (authority == null && query == null && fragment == null) {
        return this
    }
    return URI(
        scheme,
        null,
        requireNotNull(path),
        null,
        null,
    )
}
