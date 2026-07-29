package com.artemchep.keyguard.util.io

import platform.Foundation.NSURL

actual fun LocalPath.toFileUriString(): String =
    requireNotNull(toNSURL().absoluteString)

actual fun String.toLocalPathFromFileUriOrNull(): LocalPath? = runCatching {
    val url = requireNotNull(NSURL.URLWithString(this))
    require(url.isFileURL())
    require(url.host.isNullOrEmpty() || url.host.equals("localhost", ignoreCase = true))
    LocalPath(requireNotNull(url.path))
}.getOrNull()
