package com.artemchep.keyguard.common.service.zip

import kotlinx.io.Sink

interface ZipService {
    suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    )
}
