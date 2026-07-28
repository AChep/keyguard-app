package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.writeBytes
import com.artemchep.keyguard.common.service.file.toFileUriString
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import kotlinx.io.Sink
import kotlinx.io.files.SystemFileSystem

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
        path.toKotlinxIoPath()
            .parent
            ?.let(SystemFileSystem::createDirectories)
        path.writeBytes(data)
    }

    is DownloadWriter.SinkWriter -> {
        sink.write(data)
        sink.flush()
    }
}

fun DownloadWriter.locationUri(): String? = when (this) {
    is DownloadWriter.LocalPathWriter -> path.toFileUriString()
    is DownloadWriter.SinkWriter -> null
}
