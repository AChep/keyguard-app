package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.download.util.throwIfDownloadFailed
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.asSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Suppress("TooGenericExceptionCaught")
class DownloadTaskImpl internal constructor(
    private val httpClient: HttpClient,
    private val fileEncryptionCodec: FileEncryptionCodec,
    private val stagingSpoolFactory: StagingSpoolFactory =
        DefaultStagingSpoolFactory(),
) : DownloadTask {
    constructor(
        directDI: DirectDI,
    ) : this(
        httpClient = directDI.instance(),
        fileEncryptionCodec = directDI.instance(),
        stagingSpoolFactory = directDI.instance(),
    )

    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = flow<DownloadProgress> {
        val result = try {
            val plainBytes = key?.let { fileEncryptionCodec.decrypt(data, it) } ?: data
            writer.writeBytes(plainBytes)
            writer.locationUri().right()
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            e.left()
        }
        emit(DownloadProgress.Complete(result))
    }.onStart {
        emit(DownloadProgress.Loading())
    }

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = channelFlow {
        send(DownloadProgress.Loading())

        val downloadContext = currentCoroutineContext()
        val result = try {
            withContext(Dispatchers.IO) {
                httpClient.prepareGet(url) {
                    onDownload { downloaded, total ->
                        trySend(
                            DownloadProgress.Loading(
                                downloaded = downloaded,
                                total = total,
                            ),
                        )
                    }
                }.execute { response ->
                    response.status.throwIfDownloadFailed()
                    response.bodyAsChannel()
                        .asSource()
                        .withCancellationChecks(downloadContext::ensureActive)
                        .buffered()
                        .use { source ->
                            writer.writeSource(
                                source = source,
                                key = key,
                                fileEncryptionCodec = fileEncryptionCodec,
                                stagingSpoolFactory = stagingSpoolFactory,
                                checkCancellation = downloadContext::ensureActive,
                            )
                        }
                }
            }
            writer.locationUri().right()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            e.throwIfFatalOrCancellation()
            e.left()
        }

        send(DownloadProgress.Complete(result))
    }
}

private fun RawSource.withCancellationChecks(
    checkCancellation: () -> Unit,
): RawSource = object : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        checkCancellation()
        val read = this@withCancellationChecks.readAtMostTo(sink, byteCount)
        checkCancellation()
        return read
    }

    override fun close() = this@withCancellationChecks.close()
}
