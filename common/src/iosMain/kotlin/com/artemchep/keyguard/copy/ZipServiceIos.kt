package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import kotlinx.io.Sink

object ZipServiceIos : ZipService {
    override suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        throw UnsupportedOperationException("ZIP export is not supported on iOS yet.")
    }
}
