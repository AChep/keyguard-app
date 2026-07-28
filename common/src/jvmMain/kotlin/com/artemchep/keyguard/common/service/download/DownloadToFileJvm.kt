package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.exception.HttpException
import io.ktor.http.HttpStatusCode
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val DOWNLOAD_PROGRESS_POOLING_PERIOD_MS = 1000L

internal suspend fun ProducerScope<DownloadProgress>.downloadToFileJvm(
    okHttpClient: OkHttpClient,
    src: String,
    dst: File,
): File {
    val request = Request.Builder()
        .get()
        .url(src)
        .build()
    return okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw HttpException(
                statusCode = HttpStatusCode.fromValue(response.code),
                m = response.message,
                e = null,
            )
        }
        val responseBody = response.body

        //
        // Check if the file is already loaded
        //

        val dstContentLength = dst.length()
        val srcContentLength = responseBody.contentLength()
        if (dstContentLength == srcContentLength) {
            return@use dst
        }

        dst.delete()
        dst.parentFile?.mkdirs()

        coroutineScope {
            val totalBytesWritten = atomic(0L)

            val monitorJob = launch {
                delay(DOWNLOAD_PROGRESS_POOLING_PERIOD_MS / 2)
                while (isActive) {
                    val event = DownloadProgress.Loading(
                        downloaded = totalBytesWritten.value,
                        total = srcContentLength,
                    )
                    trySend(event)

                    // Wait a bit before the next status update.
                    delay(DOWNLOAD_PROGRESS_POOLING_PERIOD_MS)
                }
            }

            withContext(Dispatchers.IO) {
                responseBody.byteStream().use { inputStream ->
                    dst.outputStream().use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val bytes = inputStream.read(buffer)
                            if (bytes != -1) {
                                outputStream.write(buffer, 0, bytes)
                                totalBytesWritten.addAndGet(bytes.toLong())
                            } else {
                                break
                            }
                        }
                    }
                }
            }
            monitorJob.cancelAndJoin()
        }

        dst
    }
}
