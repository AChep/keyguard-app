package com.artemchep.keyguard.common.service.file

import java.io.File

internal actual fun fileLastModifiedMillis(path: String): Long? =
    runCatching {
        // File.lastModified() returns 0L when the file does not exist or the
        // time cannot be read; treat that as "unknown".
        File(path).lastModified().takeIf { it > 0L }
    }.getOrNull()
