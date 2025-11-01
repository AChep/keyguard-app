package com.artemchep.keyguard.common.service.download

import io.ktor.utils.io.core.use
import java.io.File
import java.io.OutputStream
import kotlin.io.writeBytes

sealed interface DownloadWriter {
    data class FileWriter(
        val file: File,
    ) : DownloadWriter

    data class StreamWriter(
        val outputStream: OutputStream,
    ) : DownloadWriter
}

fun DownloadWriter.writeBytes(data: ByteArray) = when (this) {
    is DownloadWriter.FileWriter -> {
        file.writeBytes(data)
    }

    is DownloadWriter.StreamWriter -> {
        // Convert the data into a stream and copy
        // it over into the output stream.
        data.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}
