package com.artemchep.keyguard.common.service.download.util

import com.artemchep.keyguard.common.exception.HttpException
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray

private const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 16 * 1024

internal fun HttpRequestBuilder.disableCache() {
    header(HttpHeaders.CacheControl, "no-store")
}

suspend fun HttpClient.downloadToByteArray(
    url: String,
    bufferSize: Int = DEFAULT_DOWNLOAD_BUFFER_SIZE,
    validateSize: (Long) -> Unit = {},
    onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
): ByteArray {
    val output = Buffer()
    downloadToSink(
        url = url,
        output = output,
        bufferSize = bufferSize,
        validateSize = validateSize,
        onProgress = onProgress,
    )
    return output.readByteArray()
}

private suspend fun HttpClient.downloadToSink(
    url: String,
    output: Sink,
    bufferSize: Int,
    validateSize: (Long) -> Unit,
    onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
) {
    val response = get(url) {
        disableCache()
    }
    response.status.throwIfDownloadFailed()

    val total = response.headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
    total?.let(validateSize)

    val channel = response.bodyAsChannel()
    val buffer = ByteArray(bufferSize)
    var downloaded = 0L
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
            output.write(buffer, 0, read)
            onProgress(downloaded, total)
        }
        output.flush()
    } finally {
        if (!channel.isClosedForRead) {
            channel.cancel(null)
        }
    }
}

internal fun HttpStatusCode.throwIfDownloadFailed() {
    if (isSuccess()) {
        return
    }

    throw HttpException(
        statusCode = this,
        m = "Failed to download attachment: HTTP $value.",
        e = null,
    )
}
