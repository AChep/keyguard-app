package com.artemchep.keyguard.common.service.download

import java.io.File
import java.io.OutputStream

sealed interface DownloadWriter {
    data class FileWriter(
        val file: File,
    ) : DownloadWriter

    data class StreamWriter(
        val outputStream: OutputStream,
    ) : DownloadWriter
}