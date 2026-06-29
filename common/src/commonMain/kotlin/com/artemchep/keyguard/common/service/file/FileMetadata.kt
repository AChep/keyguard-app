package com.artemchep.keyguard.common.service.file

import kotlin.time.Instant

data class FileMetadata(
    val lastModified: Instant?,
    val size: Long?,
)
