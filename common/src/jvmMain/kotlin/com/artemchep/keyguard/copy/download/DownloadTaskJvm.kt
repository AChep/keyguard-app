package com.artemchep.keyguard.copy.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File
import java.io.IOException
import java.io.OutputStream

open class DownloadTaskJvm(
    private val cacheDirProvider: CacheDirProvider,
    private val cryptoGenerator: CryptoGenerator,
    private val okHttpClient: OkHttpClient,
    private val fileEncryptor: FileEncryptor,
) : DownloadTask {
    companion object {
        private const val DOWNLOAD_PROGRESS_POOLING_PERIOD_MS = 1000L
    }

    constructor(
        directDI: DirectDI,
        cacheDirProvider: CacheDirProvider,
    ) : this(
        cacheDirProvider = cacheDirProvider,
        cryptoGenerator = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = flow {
        val f = channelFlow<DownloadProgress> {
            // 1. Create a temp file to write encrypted download into
            // we use this file to make the situation where the real file is
            // half loaded less likely.
            val cacheFile = kotlin.runCatching {
                val cacheFileName = cryptoGenerator.uuid() + ".download"
                val cacheFileRelativePath = "download_cache/$cacheFileName"
                cacheDirProvider.get().resolve(cacheFileRelativePath)
            }.getOrElse { e ->
                // Report the download as failed if we could not
                // resolve a cache file.
                val event = DownloadProgress.Complete(
                    result = e.left(),
                )
                send(event)
                return@channelFlow
            }

            // 2. Download the encrypted content of a file
            // to the temporary file.
            val result = try {
                flap(
                    src = url,
                    dst = cacheFile,
                )
            } catch (e: Exception) {
                // Delete cache file in case of
                // an error.
                runCatching {
                    cacheFile.delete()
                }

                e.throwIfFatalOrCancellation()

                val result = e.left()
                DownloadProgress.Complete(
                    result = result,
                )
            }
            send(result)
        }
            .flatMapConcat { event ->
                when (event) {
                    is DownloadProgress.Complete ->
                        event.result
                            .fold(
                                ifLeft = {
                                    flowOf(event)
                                },
                                ifRight = { tmpFile ->
                                    // Decrypt the file and move it to the final
                                    // destination.
                                    flow {
                                        emit(DownloadProgress.Loading())
                                        val result = kotlin
                                            .runCatching {
                                                tmpFile.decryptAndMove(
                                                    key = key,
                                                    writer = writer,
                                                )
                                            }
                                            .fold(
                                                onFailure = { e ->
                                                    e.printStackTrace()
                                                    e.left()
                                                },
                                                onSuccess = {
                                                    when (writer) {
                                                        is DownloadWriter.FileWriter -> writer.file.right()
                                                        is DownloadWriter.StreamWriter -> File(".").right()
                                                    }
                                                },
                                            )
                                        emit(DownloadProgress.Complete(result))
                                    }
                                },
                            )

                    is DownloadProgress.Loading -> flowOf(event)
                    is DownloadProgress.None -> flowOf(event)
                }
            }
        emitAll(f)
    }
        .onStart {
            val initialState = DownloadProgress.Loading()
            emit(initialState)
        }

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter,
    ) = when (writer) {
        is DownloadWriter.FileWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )

        is DownloadWriter.StreamWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )
    }

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.FileWriter,
    ) = withContext(Dispatchers.IO) {
        val dst = writer.file
        dst.parentFile?.mkdirs()
        dst.delete()

        decryptAndMove(
            key = key,
            stream = dst.outputStream(),
        )
    }

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.StreamWriter,
    ) = withContext(Dispatchers.IO) {
        decryptAndMove(
            key = key,
            stream = writer.outputStream,
        )
    }

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        stream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        inputStream()
            .use { fis ->
                if (key != null) {
                    fileEncryptor
                        .decode(
                            input = fis,
                            key = key,
                        )
                        .use { i -> i.copyTo(stream) }
                } else {
                    fis.copyTo(stream)
                }
            }
    }

    private suspend fun ProducerScope<DownloadProgress>.flap(
        src: String,
        dst: File,
    ): DownloadProgress.Complete {
        println("Downloading $src")
        val response = kotlin.run {
            val request = Request.Builder()
                .get()
                .url(src)
                .build()
            okHttpClient.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            val exception = HttpException(
                statusCode = HttpStatusCode.fromValue(response.code),
                m = response.message,
                e = null,
            )
            val result = exception.left()
            return DownloadProgress.Complete(
                result = result,
            )
        }
        val responseBody = response.body
            ?: throw IOException("File is not available!")

        //
        // Check if the file is already loaded
        //

        val dstContentLength = dst.length()
        val srcContentLength = responseBody.contentLength()
        if (dstContentLength == srcContentLength) {
            val result = dst.right()
            return DownloadProgress.Complete(
                result = result,
            )
        }

        dst.delete()
        dst.parentFile?.mkdirs()

        coroutineScope {
            var totalBytesWritten = 0L

            val monitorJob = launch {
                delay(DOWNLOAD_PROGRESS_POOLING_PERIOD_MS / 2)
                while (isActive) {
                    val event = DownloadProgress.Loading(
                        downloaded = totalBytesWritten,
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
                                totalBytesWritten += bytes
                            } else {
                                break
                            }
                        }
                    }
                }
            }
            monitorJob.cancelAndJoin()
        }

        val result = dst.right()
        return DownloadProgress.Complete(
            result = result,
        )
    }
}
