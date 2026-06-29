package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.util.copyDecryptedToLocalFileAfterVerification
import com.artemchep.keyguard.common.service.download.util.copyDecryptedToSinkAfterVerification
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.platform.toJavaFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.io.asOutputStream
import okhttp3.OkHttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

open class DownloadTaskJvm(
    private val cacheDirProvider: CacheDirProvider,
    private val cryptoGenerator: CryptoGenerator,
    private val okHttpClient: OkHttpClient,
    private val fileEncryptor: FileEncryptor,
) : DownloadTask {
    constructor(
        directDI: DirectDI,
    ) : this(
        cacheDirProvider = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )

    private suspend fun getCacheFile(): File {
        val cacheFileName = cryptoGenerator.uuid() + ".download"
        val cacheFileRelativePath = "download_cache/$cacheFileName"
        return cacheDirProvider.get()
            .resolve(cacheFileRelativePath)
            .toJavaFile()
    }

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
    ): Flow<DownloadProgress> = flow {
        val f = channelFlow<DownloadProgress> {
            // 1. Create a temp file to write encrypted download into
            // we use this file to make the situation where the real file is
            // half loaded less likely.
            val cacheFile = kotlin.runCatching {
                getCacheFile()
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
                downloadToFileJvm(
                    okHttpClient = okHttpClient,
                    src = url,
                    dst = cacheFile,
                )

                cacheFile.decryptAndMove(
                    key = key,
                    writer = writer,
                )

                val location = when (writer) {
                    is DownloadWriter.LocalPathWriter -> writer.path.toJavaFile().toURI().toString()
                    is DownloadWriter.SinkWriter -> null
                }
                DownloadProgress.Complete(location.right())
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                e.printStackTrace()

                val result = e.left()
                DownloadProgress.Complete(
                    result = result,
                )
            } finally {
                runCatching {
                    cacheFile.delete()
                }
            }
            send(result)
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
        is DownloadWriter.LocalPathWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )

        is DownloadWriter.SinkWriter -> decryptAndMove(
            key = key,
            writer = writer,
        )
    }

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.LocalPathWriter,
    ) = copyDecryptedToLocalFileAfterVerification(
        dst = writer.path.toJavaFile(),
        key = key,
        fileEncryptor = fileEncryptor,
    )

    private suspend fun File.decryptAndMove(
        key: ByteArray?,
        writer: DownloadWriter.SinkWriter,
    ) {
        val stream = writer.sink.asOutputStream()
        copyDecryptedToSinkAfterVerification(
            key = key,
            fileEncryptor = fileEncryptor,
            output = stream,
        )
        writer.sink.flush()
    }
}
