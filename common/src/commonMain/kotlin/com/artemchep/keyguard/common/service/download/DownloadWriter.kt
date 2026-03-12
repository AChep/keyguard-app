package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.writeBytes
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.io.Sink

sealed interface DownloadWriter {
    data class LocalPathWriter(
        val path: LocalPath,
    ) : DownloadWriter

    data class SinkWriter(
        val sink: Sink,
    ) : DownloadWriter
}

fun DownloadWriter.writeBytes(data: ByteArray) = when (this) {
    is DownloadWriter.LocalPathWriter -> {
        path.writeBytes(data)
    }

    is DownloadWriter.SinkWriter -> {
        sink.write(data)
        sink.flush()
    }
}
