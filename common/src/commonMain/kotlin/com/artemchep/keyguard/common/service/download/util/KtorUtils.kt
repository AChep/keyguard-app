package com.artemchep.keyguard.common.service.download.util

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 16 * 1024

suspend fun HttpClient.downloadToByteArray(
    url: String,
    bufferSize: Int = DEFAULT_DOWNLOAD_BUFFER_SIZE,
    validateSize: (Long) -> Unit = {},
    onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
): ByteArray {
    val output = Buffer()
    downloadToSink(
        url = url,
        output = { output },
        closeOutput = false,
        bufferSize = bufferSize,
        validateSize = validateSize,
        onProgress = onProgress,
    )
    return output.readByteArray()
}

suspend fun HttpClient.downloadToFile(
    url: String,
    output: LocalPath,
    bufferSize: Int = DEFAULT_DOWNLOAD_BUFFER_SIZE,
    validateSize: (Long) -> Unit = {},
    onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
): LocalPath {
    val outputPath = output.toKotlinxIoPath()
    downloadToSink(
        url = url,
        output = {
            outputPath.parent?.let(SystemFileSystem::createDirectories)
            SystemFileSystem.sink(outputPath)
                .buffered()
        },
        closeOutput = true,
        bufferSize = bufferSize,
        validateSize = validateSize,
        onProgress = onProgress,
    )
    return output
}

private suspend fun HttpClient.downloadToSink(
    url: String,
    output: () -> Sink,
    closeOutput: Boolean,
    bufferSize: Int,
    validateSize: (Long) -> Unit,
    onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
) {
    val response = get(url)
    response.status.throwIfDownloadFailed()

    val total = response.headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
    total?.let(validateSize)

    val channel = response.bodyAsChannel()
    val buffer = ByteArray(bufferSize)
    var downloaded = 0L
    val sink = try {
        output()
    } catch (e: Throwable) {
        if (!channel.isClosedForRead) {
            channel.cancel(null)
        }
        throw e
    }
    try {
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) {
                break
            }
            if (read == 0) {
                continue
            }

            downloaded += read
            validateSize(downloaded)
            sink.write(buffer, 0, read)
            onProgress(downloaded, total)
        }
        sink.flush()
    } finally {
        try {
            if (closeOutput) {
                sink.close()
            }
        } finally {
            if (!channel.isClosedForRead) {
                channel.cancel(null)
            }
        }
    }
}

private fun HttpStatusCode.throwIfDownloadFailed() {
    if (isSuccess()) {
        return
    }

    throw HttpException(
        statusCode = this,
        m = "Failed to download attachment: HTTP $value.",
        e = null,
    )
}
