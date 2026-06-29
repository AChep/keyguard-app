package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.util.downloadToFile
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.platform.toKotlinxIoPath
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val DOWNLOAD_FILE_BUFFER_SIZE = 16 * 1024

class DownloadTaskIos(
    private val cacheDirProvider: CacheDirProvider,
    private val cryptoGenerator: CryptoGenerator,
    private val httpClient: HttpClient,
    private val fileEncryptor: FileEncryptor,
) : DownloadTask {
    constructor(
        directDI: DirectDI,
    ) : this(
        cacheDirProvider = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        httpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )

    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = downloadDataToWriter(
        data = data,
        key = key,
        writer = writer,
        fileEncryptor = fileEncryptor,
    )

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = flow<DownloadProgress> {
        val result = try {
            val cacheFile = getCacheFile()
            try {
                httpClient.downloadToFile(url, cacheFile) { downloaded, total ->
                    emit(
                        DownloadProgress.Loading(
                            downloaded = downloaded,
                            total = total,
                        ),
                    )
                }
                cacheFile.decryptAndMove(
                    key = key,
                    writer = writer,
                )
                writer.locationUri().right()
            } finally {
                runCatching {
                    cacheFile.deleteIfExists()
                }
            }
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            e.left()
        }
        emit(DownloadProgress.Complete(result))
    }.onStart {
        emit(DownloadProgress.Loading())
    }

    private suspend fun getCacheFile(): LocalPath {
        val cacheFileName = cryptoGenerator.uuid() + ".download"
        return cacheDirProvider.get()
            .resolve("download_cache", cacheFileName)
    }

    private fun LocalPath.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter,
    ) = when (writer) {
        is DownloadWriter.LocalPathWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )

        is DownloadWriter.SinkWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )
    }

    private fun LocalPath.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.LocalPathWriter,
    ) {
        val tmp = writer.path.tempSibling("download")
        try {
            if (key != null) {
                decryptTo(
                    key = key,
                    output = tmp,
                )
            } else {
                copyToFile(tmp)
            }
            tmp.replaceWith(writer.path)
        } catch (e: Throwable) {
            runCatching {
                tmp.deleteIfExists()
            }
            throw e
        }
    }

    private fun LocalPath.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.SinkWriter,
    ) {
        if (key == null) {
            copyToSink(writer.sink)
            writer.sink.flush()
            return
        }

        val tmp = tempSibling("plain")
        try {
            decryptTo(
                key = key,
                output = tmp,
            )
            tmp.copyToSink(writer.sink)
            writer.sink.flush()
        } finally {
            runCatching {
                tmp.deleteIfExists()
            }
        }
    }

    private fun LocalPath.decryptTo(
        key: ByteArray,
        output: LocalPath,
    ) {
        SystemFileSystem.source(toKotlinxIoPath())
            .buffered()
            .use { source ->
                fileEncryptor.decode(
                    input = source,
                    output = output,
                    key = key,
                )
            }
    }

    private fun LocalPath.copyToFile(
        output: LocalPath,
    ) {
        val outputPath = output.toKotlinxIoPath()
        outputPath.parent?.let(SystemFileSystem::createDirectories)
        SystemFileSystem.source(toKotlinxIoPath())
            .buffered()
            .use { source ->
                SystemFileSystem.sink(outputPath)
                    .buffered()
                    .use { sink ->
                        copyToSink(
                            source = source,
                            sink = sink,
                        )
                    }
            }
    }

    private fun LocalPath.copyToSink(
        sink: Sink,
    ) {
        SystemFileSystem.source(toKotlinxIoPath())
            .buffered()
            .use { source ->
                copyToSink(
                    source = source,
                    sink = sink,
                )
            }
    }

    private fun LocalPath.replaceWith(
        destination: LocalPath,
    ) {
        val sourcePath = toKotlinxIoPath()
        val destinationPath = destination.toKotlinxIoPath()
        destinationPath.parent?.let(SystemFileSystem::createDirectories)
        if (SystemFileSystem.exists(destinationPath)) {
            SystemFileSystem.delete(destinationPath)
        }

        try {
            SystemFileSystem.atomicMove(
                source = sourcePath,
                destination = destinationPath,
            )
        } catch (e: Throwable) {
            try {
                copyToFile(destination)
            } catch (copyError: Throwable) {
                runCatching {
                    destination.deleteIfExists()
                }
                throw copyError
            } finally {
                runCatching {
                    deleteIfExists()
                }
            }
        }
    }

    private fun LocalPath.deleteIfExists() {
        val path = toKotlinxIoPath()
        if (SystemFileSystem.exists(path)) {
            SystemFileSystem.delete(path)
        }
    }

    private fun LocalPath.tempSibling(
        suffix: String,
    ): LocalPath = LocalPath("$value.${cryptoGenerator.uuid()}.$suffix.tmp")

    private fun copyToSink(
        source: Source,
        sink: Sink,
    ) {
        val buffer = ByteArray(DOWNLOAD_FILE_BUFFER_SIZE)
        while (true) {
            val read = source.readAtMostTo(buffer)
            if (read == -1) {
                break
            }
            if (read == 0) {
                continue
            }
            sink.write(buffer, 0, read)
        }
        sink.flush()
    }
}
