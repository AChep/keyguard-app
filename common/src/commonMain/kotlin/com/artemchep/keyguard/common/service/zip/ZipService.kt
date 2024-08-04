package com.artemchep.keyguard.common.service.zip

import java.io.OutputStream

interface ZipService {
    suspend fun zip(
        outputStream: OutputStream,
        config: ZipConfig,
        entries: List<ZipEntry>,
    )
}
