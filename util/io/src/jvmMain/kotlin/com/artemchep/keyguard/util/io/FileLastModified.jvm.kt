package com.artemchep.keyguard.util.io

import java.io.File

actual fun LocalPath.lastModifiedMillis(): Long? =
    runCatching {
        // File.lastModified() returns 0L when the file does not exist or the
        // time cannot be read; treat that as "unknown".
        File(value).lastModified().takeIf { it > 0L }
    }.getOrNull()
